"""Measured columns of the dungeon audit table, from the exports (offline).

  py -3 -I tools/qa/dungeon_stats.py [--exports mod/run/qa-exports] [ids...]

Prints a markdown table: rooms (grand caverns in brackets), mobs (dormant), containers, lecterns with books, the
number of distinct room purposes, the share of the commonest material among built+natural blocks, the biggest
cavern's size and how many rooms are empty of enemies. stdlib + numpy only.
"""
import argparse
import collections
import glob
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import dungeon_export  # noqa: E402

FLAG_GRAND = 2048


def row(path):
    ex = dungeon_export.load(path)
    rooms = [p for p in ex.pieces if p['kind'] == 'room']
    grand = [p for p in rooms if p['flags'] & FLAG_GRAND]
    mobs = [e for e in ex.entities if 'dormant' in e]
    asleep = sum(1 for e in mobs if e.get('dormant'))
    chests = len(ex.containers)
    books = sum(1 for l in ex.lecterns if l.get('book'))
    purposes = collections.Counter()
    empty = 0
    for r in rooms:
        for f in r.get('features', []):
            if f.startswith('room:'):
                purposes[f] += 1
        x1, y1, z1, x2, y2, z2 = r['box']
        n = sum(1 for e in mobs if x1 <= e['x'] <= x2 and y1 <= e['y'] <= y2 and z1 <= e['z'] <= z2)
        if n == 0:
            empty += 1
    # material share over non-air, non-fluid blocks of the piece boxes' union (rock included)
    A = ex.A
    ids, cnt = np.unique(A[A != 0], return_counts=True)
    names = [ex.names[i] for i in ids]
    solid = [(c, n) for c, n in zip(cnt, names) if not n.endswith(('water', 'lava'))]
    tot = sum(c for c, _ in solid)
    top = max(solid)[0] / tot if tot else 0
    kinds = len({n for _, n in solid})
    big = max(((p['box'][3] - p['box'][0] + 1, p['box'][5] - p['box'][2] + 1, p['box'][4] - p['floor']) for p in grand),
              default=None)
    return {
        'id': ex.id, 'theme': ex.meta.get('theme'), 'rooms': len(rooms), 'grand': len(grand), 'mobs': len(mobs), 'asleep': asleep,
        'chests': chests, 'lecterns': books, 'purposes': len(purposes), 'empty': empty, 'blocks': kinds, 'top': top, 'big': big,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('ids', nargs='*')
    ap.add_argument('--exports', default=os.path.join(HERE, '..', '..', 'mod', 'run', 'qa-exports'))
    a = ap.parse_args()
    paths = [os.path.join(a.exports, i + '.json.gz') for i in a.ids] if a.ids else sorted(glob.glob(os.path.join(a.exports, '*.json.gz')))
    print('| dungeon | theme | rooms | grand | mobs (asleep) | containers | lecterns | purposes | empty rooms | block kinds | top block | biggest cavern |')
    print('|---|---|---|---|---|---|---|---|---|---|---|---|')
    for p in paths:
        r = row(p)
        big = '%dx%d, %d tall' % r['big'] if r['big'] else '-'
        print('| %s | %s | %d | %d | %d (%d) | %d | %d | %d | %d | %d | %d%% | %s |' % (
            r['id'], r['theme'], r['rooms'], r['grand'], r['mobs'], r['asleep'], r['chests'], r['lecterns'], r['purposes'],
            r['empty'], r['blocks'], round(r['top'] * 100), big))


if __name__ == '__main__':
    main()
