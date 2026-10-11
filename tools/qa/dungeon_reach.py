"""Reachability check on dungeon exports (no Minecraft, no world): flood-fills the exported blocks from the entrance with
Minecraft walking rules and reports rooms, chests, lecterns, spawners and the boss that cannot be walked to.

usage:
  py -3 -I tools/qa/dungeon_reach.py <export.json.gz | dir> ... [--md out.md] [--json out.json]

Walking rules (per column cell = feet position):
  * body fits: the feet cell and the cell above are passable (air, low steps, stairs, open doors/gates, water,
    ladders); a low step or stair needs one more cell of headroom
  * ground: a solid/low/stair block below (or standing in water / on a ladder)
  * moves: one block sideways; up 1 (needs 3 cells of headroom at the start); down up to 3; climbing ladders and
    swimming move vertically; never through full or tall blocks (walls, fences) or lava
  * iron doors and claw-door rings count as locked (strict) and as open in the lenient run ("mechanisms open")
Seeds: the entrance tunnel's surface end (and, as a second run, any open-sky standable cell of the export).
"""
import argparse
import glob
import json
import os
import sys
from collections import deque

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dungeon_export  # noqa: E402

PASS = set('alsdwc')
SUPPORT = set('flst')


class Grid:
    def __init__(self, ex, open_mech):
        A = ex.A
        cls = np.frombuffer(ex.coll.encode('ascii'), np.uint8)
        C = cls[A]
        ch = lambda s: np.isin(C, np.frombuffer(s.encode(), np.uint8))
        body = ch('alsdwc' + ('m' if open_mech else ''))
        sup = ch('flst')
        selfsup = ch('lscw')
        lowst = ch('ls')
        Y, Z, X = A.shape
        self.Y, self.Z, self.X = Y, Z, X
        P = 3
        Yp, Zp, Xp = Y + 2 * P, Z + 2, X + 2

        def pad(a, top):
            o = np.zeros((Yp, Zp, Xp), bool)
            o[P:P + Y, 1:1 + Z, 1:1 + X] = a
            if top:
                o[P + Y:, 1:1 + Z, 1:1 + X] = True       # open sky above the export
            return o
        b = pad(body, True)
        s = pad(sup, False)
        ss = pad(selfsup, False)
        ls = pad(lowst, False)
        v = np.zeros_like(b)
        v[1:-2] = b[1:-2] & b[2:-1] & (s[:-3] | ss[1:-2]) & (~ls[1:-2] | b[3:])
        self.body = bytearray(b.ravel().astype(np.uint8).tobytes())
        self.valid_np = v
        self.valid = bytearray(v.ravel().astype(np.uint8).tobytes())
        self.climb = bytearray(pad(ch('cw'), False).ravel().astype(np.uint8).tobytes())
        self.P, self.Yp, self.Zp, self.Xp = P, Yp, Zp, Xp
        self.sy, self.sz = Zp * Xp, Xp
        self.sky = None

    def idx(self, x, y, z, box):
        x1, y1, z1 = box[0], box[1], box[2]
        return ((y - y1 + self.P) * self.Zp + (z - z1 + 1)) * self.Xp + (x - x1 + 1)

    def inside(self, x, y, z, box):
        return box[0] <= x <= box[3] and box[1] <= y <= box[4] and box[2] <= z <= box[5]

    def flood(self, seeds):
        body, valid, climb = self.body, self.valid, self.climb
        sy, sz = self.sy, self.sz
        n_all = len(body)
        seen = bytearray(n_all)
        q = deque()
        for s in seeds:
            if not seen[s]:
                seen[s] = 1
                q.append(s)
        offs = (sz, -sz, 1, -1)
        while q:
            n = q.popleft()
            for o in offs:
                m = n + o
                if valid[m]:
                    if not seen[m]:
                        seen[m] = 1
                        q.append(m)
                    continue
                u = m + sy
                if valid[u] and body[n + 2 * sy]:
                    if not seen[u]:
                        seen[u] = 1
                        q.append(u)
                    continue
                if body[m] and body[u]:
                    for d in (1, 2, 3):
                        k = m - d * sy
                        if valid[k]:
                            if not seen[k]:
                                seen[k] = 1
                                q.append(k)
                            break
                        if not body[k]:
                            break
            if climb[n]:
                up = n + sy
                if body[up] and body[up + sy] and not seen[up]:
                    seen[up] = 1
                    q.append(up)
                dn = n - sy
                if body[dn] and not seen[dn]:
                    seen[dn] = 1
                    q.append(dn)
        return np.frombuffer(bytes(seen), np.uint8).reshape(self.Yp, self.Zp, self.Xp).astype(bool)

    def entrance_seeds(self, ex):
        seeds = []
        v = self.valid_np
        for e in ex.entrances:
            for dx in range(-2, 3):
                for dz in range(-2, 3):
                    x, z = e['x'] + dx, e['z'] + dz
                    for y in range(e['y'] - 3, e['y'] + 4):
                        if self.inside(x, y, z, ex.box):
                            i = self.idx(x, y, z, ex.box)
                            if self.valid[i]:
                                seeds.append(i)
        return seeds

    def sky_seeds(self, ex):
        """Standable cells with open sky above (the surface around and over the structure)."""
        v = self.valid_np
        body = np.frombuffer(bytes(self.body), np.uint8).reshape(v.shape).astype(bool)
        # open[y] = every cell from y up is passable
        openup = np.logical_and.accumulate(body[::-1], axis=0)[::-1]
        ok = v & openup
        # only the real box: padding columns are never valid
        ys, zs, xs = np.nonzero(ok)
        i = (ys * self.Zp + zs) * self.Xp + xs
        return i.tolist()


def near(grid, reached, ex, x, y, z, rh=2, below=2, above=1):
    """True if a reached standing cell is within reach of block (x, y, z)."""
    x1, y1, z1 = ex.box[0], ex.box[1], ex.box[2]
    xi, zi = x - x1 + 1, z - z1 + 1
    ya = y - below - y1 + grid.P
    yb = y + above - y1 + grid.P
    ya = max(0, ya)
    yb = min(grid.Yp - 1, yb)
    if yb < ya:
        return False
    sub = reached[ya:yb + 1, max(0, zi - rh):zi + rh + 1, max(0, xi - rh):xi + rh + 1]
    return bool(sub.any())


def room_hit(grid, reached, ex, box):
    x1, y1, z1 = ex.box[0], ex.box[1], ex.box[2]
    ya = max(0, box[1] - y1 + grid.P)
    yb = min(grid.Yp - 1, box[4] - y1 + grid.P + 1)
    za, zb = max(0, box[2] - z1 + 1), min(grid.Zp - 1, box[5] - z1 + 1)
    xa, xb = max(0, box[0] - x1 + 1), min(grid.Xp - 1, box[3] - x1 + 1)
    return bool(reached[ya:yb + 1, za:zb + 1, xa:xb + 1].any())


def analyse(ex):
    out = {'id': ex.id, 'theme': ex.meta.get('theme'), 'structure': ex.meta.get('structure'),
           'entrances': len(ex.entrances)}
    rooms = dungeon_export.rooms(ex)
    out['rooms'] = len(rooms)
    chests = ex.containers
    lecs = [l for l in ex.lecterns if l.get('book', True)]
    mobs = [e for e in ex.entities if 'dormant' in e]
    bosses = [e for e in mobs if e.get('boss')]
    out['chests'] = len(chests)
    out['lecterns'] = len(lecs)
    out['mobs'] = len(mobs)
    out['spawners'] = len(ex.spawners)
    out['bosses'] = len(bosses)
    runs = {}
    for name, open_mech, seed_kind in (('strict', False, 'entrance'), ('open', True, 'entrance'), ('surface', True, 'sky')):
        g = Grid(ex, open_mech)
        seeds = g.entrance_seeds(ex) if seed_kind == 'entrance' else g.sky_seeds(ex)
        r = g.flood(seeds)
        res = {'seeds': len(seeds), 'cells': int(r.sum())}
        res['rooms_unreached'] = [
            {'idx': i, 'role': rm['role'], 'box': rm['box'], 'flags': rm['flags']}
            for i, rm in enumerate(rooms) if not room_hit(g, r, ex, rm['box'])]
        res['chests_unreached'] = [
            {'x': c['x'], 'y': c['y'], 'z': c['z'], 'loot': c.get('loot'), 'block': c.get('block')}
            for c in chests if not near(g, r, ex, c['x'], c['y'], c['z'])]
        res['lecterns_unreached'] = [{'x': c['x'], 'y': c['y'], 'z': c['z']} for c in lecs
                                     if not near(g, r, ex, c['x'], c['y'], c['z'])]
        res['spawners_unreached'] = [{'x': c['x'], 'y': c['y'], 'z': c['z'], 'entity': c.get('entity')}
                                     for c in ex.spawners if not near(g, r, ex, c['x'], c['y'], c['z'], rh=3, above=2)]
        res['mobs_unreached'] = [{'x': e['x'], 'y': e['y'], 'z': e['z'], 'type': e['type'], 'boss': e.get('boss', False)}
                                 for e in mobs if not near(g, r, ex, e['x'], e['y'], e['z'], rh=3, below=2, above=2)]
        res['boss_unreached'] = any(m['boss'] for m in res['mobs_unreached'])
        runs[name] = res
        del g, r
    out['runs'] = runs
    # headline: the entrance run with mechanisms operated; fall back to the surface run when there is no entrance
    head = runs['open'] if ex.entrances and runs['open']['seeds'] else runs['surface']
    out['entrance_seeded'] = bool(ex.entrances and runs['open']['seeds'])
    out['head'] = head
    out['fail'] = bool(head['rooms_unreached'] or head['chests_unreached'] or head['boss_unreached'] or head['lecterns_unreached'])
    out['fail_strict_only'] = (not out['fail']) and bool(
        runs['strict']['rooms_unreached'] or runs['strict']['chests_unreached'] or runs['strict']['boss_unreached'])
    return out


def markdown(results):
    L = ['# Dungeon reachability audit', '',
         'Flood fill over the exported blocks with Minecraft walking rules (1-block steps, 1-high jumps, 2-high clearance, '
         'drops up to 3, ladders and swimming; no walking through solid blocks). Seeds: the entrance tunnel surface end.',
         '`strict` = iron doors / claw-door rings stay shut; `open` = they were operated (the headline); '
         '`surface` = seeded from every open-sky cell instead of the entrance.', '']
    failed = [r for r in results if r['fail']]
    L.append('**%d of %d dungeons fail** (a room, chest, lectern or the boss cannot be walked to even with the '
             'mechanisms operated); %d more are only blocked behind mechanisms.' % (
                 len(failed), len(results), sum(1 for r in results if r['fail_strict_only'])))
    L.append('')
    L.append('| dungeon | theme | rooms | rooms unreached | chests unreached | lecterns unreached | boss | mobs unreached | spawners unreached | strict: rooms/chests/boss | seeded from | note |')
    L.append('|---|---|---|---|---|---|---|---|---|---|---|---|')
    for r in results:
        h = r['head']
        s = r['runs']['strict']
        note = 'FAIL' if r['fail'] else ('mechanism-gated' if r['fail_strict_only'] else 'ok')
        L.append('| %s | %s | %d | %d | %d/%d | %d/%d | %s | %d/%d | %d/%d | %d / %d / %s | %s | %s |' % (
            r['id'], r['theme'], r['rooms'], len(h['rooms_unreached']), len(h['chests_unreached']), r['chests'],
            len(h['lecterns_unreached']), r['lecterns'],
            'NO' if h['boss_unreached'] else ('yes' if r['bosses'] else 'none'),
            len(h['mobs_unreached']), r['mobs'], len(h['spawners_unreached']), r['spawners'],
            len(s['rooms_unreached']), len(s['chests_unreached']), 'NO' if s['boss_unreached'] else 'yes',
            'entrance' if r['entrance_seeded'] else 'open sky', note))
    L.append('')
    for r in failed:
        h = r['head']
        L.append('## %s (%s)' % (r['id'], r['theme']))
        for rm in h['rooms_unreached']:
            L.append('* room %d (%s) unreachable, box %s' % (rm['idx'], rm['role'], rm['box']))
        for c in h['chests_unreached']:
            L.append('* chest unreachable at %d %d %d (%s)' % (c['x'], c['y'], c['z'], c.get('loot') or c.get('block')))
        for c in h['lecterns_unreached']:
            L.append('* lectern unreachable at %d %d %d' % (c['x'], c['y'], c['z']))
        for m in h['mobs_unreached']:
            if m['boss']:
                L.append('* BOSS unreachable at %d %d %d (%s)' % (m['x'], m['y'], m['z'], m['type']))
        L.append('')
    return '\n'.join(L) + '\n'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('paths', nargs='+')
    ap.add_argument('--md')
    ap.add_argument('--json')
    a = ap.parse_args()
    files = []
    for p in a.paths:
        if os.path.isdir(p):
            files += sorted(glob.glob(os.path.join(p, '*.json.gz')))
        else:
            files.append(p)
    results = []
    for f in files:
        ex = dungeon_export.load(f)
        r = analyse(ex)
        results.append(r)
        h = r['head']
        print('%-28s rooms %2d unreached %d | chests %d/%d | boss %s | %s' % (
            r['id'], r['rooms'], len(h['rooms_unreached']), len(h['chests_unreached']), r['chests'],
            'UNREACHED' if h['boss_unreached'] else 'ok', 'FAIL' if r['fail'] else 'ok'))
    if a.md:
        os.makedirs(os.path.dirname(os.path.abspath(a.md)), exist_ok=True)
        open(a.md, 'w', encoding='utf8').write(markdown(results))
    if a.json:
        json.dump(results, open(a.json, 'w'), indent=1)
    print('%d/%d fail' % (sum(1 for r in results if r['fail']), len(results)))


if __name__ == '__main__':
    main()
