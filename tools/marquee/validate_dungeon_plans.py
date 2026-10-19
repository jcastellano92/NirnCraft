"""Static check of the marquee dungeon plans (mod/src/main/resources/nirncraft/dungeons/marquee/*.json).

usage: py -3 -I tools/marquee/validate_dungeon_plans.py [plan.json ...]

Checks what the layout code would only warn about at generation time: distinct cells, links between neighbouring
cells, corridors long enough to climb between the two door heights, a start room whose entrance door is level with
the tunnel's bottom (y + doors.s == 0), every room reachable from the start, spiral doors matching the ramp, ops that
reference books/rooms that exist. Exit code 1 on any ERR.
"""
import glob
import json
import os
import sys

CELL = 15
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
DIR = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'nirncraft', 'dungeons', 'marquee')
SIDES = {(1, 0): 'e', (-1, 0): 'w', (0, 1): 's', (0, -1): 'n'}


def side_of(a, b):
    return SIDES.get((b['at'][0] - a['at'][0], b['at'][1] - a['at'][1]))


def check(path):
    errs, warns = [], []
    plan = json.load(open(path, encoding='utf8'))
    rooms = {r['id']: r for r in plan['rooms']}
    if len(rooms) != len(plan['rooms']):
        errs.append('duplicate room ids')
    cells = {}
    for r in plan['rooms']:
        c = tuple(r['at'])
        if c in cells:
            errs.append('cell %s used by %s and %s' % (c, cells[c], r['id']))
        cells[c] = r['id']
        if abs(c[0]) > 3 or abs(c[1]) > 3:
            errs.append('%s: cell %s outside the +-3 grid' % (r['id'], c))
        if c[0] == 0 and c[1] > 0:
            errs.append('%s: cell %s is on the entrance tunnel column' % (r['id'], c))
        if r['half'][0] > 6 or r['half'][1] > 6:
            errs.append('%s: half size %s over 6' % (r['id'], r['half']))
    starts = [r for r in plan['rooms'] if r.get('role') == 'start']
    bosses = [r for r in plan['rooms'] if r.get('role') == 'boss']
    if len(starts) != 1 or len(bosses) != 1:
        errs.append('needs exactly one start and one boss room')
    adj = {k: [] for k in rooms}
    for l in plan['links']:
        a, b = rooms.get(l['a']), rooms.get(l['b'])
        if not a or not b:
            errs.append('link to unknown room %s-%s' % (l['a'], l['b']))
            continue
        sa, sb = side_of(a, b), side_of(b, a)
        if not sa:
            errs.append('link %s-%s is not between neighbouring cells' % (l['a'], l['b']))
            continue
        adj[l['a']].append(l['b'])
        adj[l['b']].append(l['a'])
        ax = 'hu' if sa in 'ew' else 'hv'
        ia = 0 if sa in 'ew' else 1
        gap = CELL - a['half'][ia] - b['half'][ia] - 1
        fa = a['y'] + a.get('doors', {}).get(sa, 0)
        fb = b['y'] + b.get('doors', {}).get(sb, 0)
        dy = abs(fa - fb)
        if gap < 1:
            errs.append('link %s-%s: rooms touch (gap %d)' % (l['a'], l['b'], gap))
        elif dy > gap - 1:
            errs.append('link %s-%s: climbs %d over a corridor of %d' % (l['a'], l['b'], dy, gap))
        if l.get('gate') and l['gate'] not in (l['a'], l['b']):
            errs.append('link %s-%s: gate room %s is not an end' % (l['a'], l['b'], l['gate']))
    if starts:
        s = starts[0]
        if s['y'] + s.get('doors', {}).get('s', 0) != 0:
            errs.append('start room: y + doors.s must be 0 (tunnel bottom), is %d' % (s['y'] + s.get('doors', {}).get('s', 0)))
        seen, todo = {s['id']}, [s['id']]
        while todo:
            for n in adj[todo.pop()]:
                if n not in seen:
                    seen.add(n)
                    todo.append(n)
        for k in rooms:
            if k not in seen:
                errs.append('room %s unreachable by links' % k)
    # ops
    for r in plan['rooms']:
        for op in r.get('ops', []):
            o = op['op']
            if o in ('lectern',) and op['book'] not in plan.get('books', {}):
                errs.append('%s: unknown book %s' % (r['id'], op['book']))
            if o == 'spiral':
                a, b = r['half'][0] - 1, r['half'][1] - 1
                n = 4 * a + 4 * b
                top = op['top']
                want = {'s': top, 'w': top - (a + b), 'n': top - (2 * a + 2 * b), 'e': top - (3 * a + 3 * b)}
                for sd, y in want.items():
                    d = r.get('doors', {}).get(sd)
                    if d is not None and d != max(0, y):
                        errs.append('%s: spiral door %s is at %d, the ramp is at %d' % (r['id'], sd, d, max(0, y)))
                if top - n > 0:
                    warns.append('%s: spiral never reaches the floor' % r['id'])
            if o == 'landing':
                need = op['y'] + op.get('depth', 3)
                have = 2 * r['half'][1] - 1
                if need > have:
                    errs.append('%s: landing of %d + %d does not fit a room %d deep' % (r['id'], op['y'], op.get('depth', 3), have))
            if o == 'chest' and 'table' not in op:
                errs.append('%s: chest without table' % r['id'])
        if r.get('role') == 'boss' and not any(op['op'] == 'boss' for op in r.get('ops', [])):
            errs.append('boss room %s has no boss op' % r['id'])
    n = len(plan['rooms'])
    if not 12 <= n <= 18:
        warns.append('%d rooms (want 12-18)' % n)
    levels = sorted({r['y'] for r in plan['rooms']})
    gates = sum(1 for l in plan['links'] if l.get('gate'))
    books = sum(1 for r in plan['rooms'] for op in r.get('ops', []) if op['op'] == 'lectern')
    info = '%s: %d rooms, %d links, %d floor levels (%d..%d), %d gates, %d lecterns' % (
        plan['id'], n, len(plan['links']), len(levels), levels[0], levels[-1], gates, books)
    return info, errs, warns


def main(argv):
    files = argv or sorted(glob.glob(os.path.join(DIR, '*.json')))
    bad = 0
    for f in files:
        if os.path.basename(f) == 'index.json':
            continue
        info, errs, warns = check(f)
        print(info)
        for w in warns:
            print('  warn', w)
        for e in errs:
            print('  ERR ', e)
            bad += 1
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
