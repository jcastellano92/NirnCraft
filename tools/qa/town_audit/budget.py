import sys, os, math, json, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from plan_audit import *

# target lots (w x d, footprint incl. 1-block margin handled by the +2 below), by building class and tier
TARGETS = {
    'capital': {'leader_hall': (31, 41), 'inn': (21, 25), 'shop': (15, 17), 'smithy': (17, 15), 'house_rich': (15, 17)},
    'city': {'leader_hall': (25, 31), 'inn': (19, 23), 'shop': (13, 15), 'smithy': (15, 13), 'house_rich': (13, 15)},
    'town': {'leader_hall': (19, 25), 'inn': (17, 21), 'shop': (13, 15), 'smithy': (15, 13), 'house_rich': (13, 13)},
    'village': {'leader_hall': (15, 19), 'inn': (15, 17), 'shop': (11, 13), 'smithy': (13, 11), 'house_rich': (11, 11)},
    'hamlet': {'leader_hall': (11, 13), 'inn': (13, 15), 'shop': (9, 11), 'smithy': (11, 9), 'house_rich': (9, 11)},
}
SOFT = {'GARDEN', 'PEN', 'FIELD', 'CAMP', 'HOMESTEAD_PLOT', 'GRAVEYARD', 'LUMBER'}
SOLID = {'WALL', 'GATE', 'TOWER', 'MINE', 'QUARRY', 'CANAL', 'DOCK', 'LANDMARK', 'TENT'}


def window_sum(I, w, d):
    # I integral image (H+1, W+1) ; returns array (H-d+1, W-w+1) of sums over d x w windows
    return I[d:, w:] - I[:-d, w:] - I[d:, :-w] + I[:-d, :-w]


def fits(free, street_near, w, d):
    H, W = free.shape
    If = np.pad(free.astype(np.int32).cumsum(0).cumsum(1), ((1, 0), (1, 0)))
    Is = np.pad(street_near.astype(np.int32).cumsum(0).cumsum(1), ((1, 0), (1, 0)))
    for (a, b) in ((w, d), (d, w)):
        if a > W or b > H:
            continue
        ok = window_sum(If, a, b) == a * b
        if ok.any() and (ok & (window_sum(Is, a, b) > 0)).any():
            return True
    return False


def settlement_budget(s):
    sid = P['sId'][s]
    tier = tier_of(sid)
    cx, cz, reach = P['sX'][s], P['sZ'][s], P['sReach'][s]
    R = int(0.85 * reach)
    R = max(R, 20)
    size = 2 * R + 1
    occ = np.zeros((size, size), np.uint8)     # 0 free, 1 soft (reclaimable), 2 occupied (building, wall, street...)
    st = np.zeros((size, size), bool)
    inside = np.zeros((size, size), bool)
    yy, xx = np.mgrid[0:size, 0:size]
    inside[:] = (xx - R) ** 2 + (yy - R) ** 2 <= R * R

    def mark(r, v, g=0):
        x0, z0, x1, z1 = r[0] - g - (cx - R), r[1] - g - (cz - R), r[2] + g - (cx - R), r[3] + g - (cz - R)
        x0, z0 = max(0, x0), max(0, z0)
        x1, z1 = min(size - 1, x1), min(size - 1, z1)
        if x0 > x1 or z0 > z1:
            return
        sub = occ[z0:z1 + 1, x0:x1 + 1]
        np.maximum(sub, v, out=sub)

    specs = [spec(i) for i in range(P['sSpecOff'][s], P['sSpecOff'][s + 1])]
    nb = 0
    area_b = 0
    area_soft = 0
    tiny = 0
    for sp in specs:
        if sp['flags'] & F_OMIT:
            continue
        k = sp['kind']
        if k in BUILDING_KINDS and k != 'TOWER':
            mark(rect(sp, 0), 2, 1)
            nb += 1
            area_b += sp['w'] * sp['d']
        elif k in SOLID and not (sp['flags'] & F_GROUND):
            if k in ('WALL',):
                continue
            mark(rect(sp, 0), 2, 0)
        elif k in SOFT and not (k == 'CAMP' and sp['variant'] == 12):
            mark(rect(sp, 0), 1)
            area_soft += sp['w'] * sp['d']
        elif k in PROP_KINDS:
            r = rect(sp, 0)
            if (r[2] - r[0] + 1) * (r[3] - r[1] + 1) <= 30:
                mark(r, 1)
            else:
                mark(r, 1)
        elif k == 'PLAZA':
            mark(rect(sp, 0), 2)
    # walls: bands from specs
    for sp in specs:
        if sp['kind'] == 'WALL' and not sp['flags'] & F_OMIT:
            n = max(1, int(math.hypot(sp['x2'] - sp['x'], sp['z2'] - sp['z'])))
            for i in range(n + 1):
                px = sp['x'] + (sp['x2'] - sp['x']) * i / n
                pz = sp['z'] + (sp['z2'] - sp['z']) * i / n
                mark((int(px), int(pz), int(px), int(pz)), 2, sp['w'] // 2 + 1)
    # streets
    for (x, z), v in street_cells(s).items():
        xi, zi = x - (cx - R), z - (cz - R)
        if 0 <= xi < size and 0 <= zi < size:
            occ[zi, xi] = 2
            st[zi, xi] = True
    # street proximity (3 blocks)
    near = st.copy()
    for _ in range(3):
        n2 = near.copy()
        n2[1:, :] |= near[:-1, :]
        n2[:-1, :] |= near[1:, :]
        n2[:, 1:] |= near[:, :-1]
        n2[:, :-1] |= near[:, 1:]
        near = n2
    strict = (occ == 0) & inside
    soft = (occ <= 1) & inside
    out = dict(radius=R, area_circle=int(inside.sum()), occupied=int(((occ == 2) & inside).sum()),
               soft_works=int(((occ == 1) & inside).sum()), free=int(strict.sum()), buildings=nb,
               street_cells=int((st & inside).sum()))
    t = TARGETS.get(tier)
    out['fit'] = {}
    if t:
        for name, (w, d) in t.items():
            # a lot needs a margin of 1 on every side
            out['fit'][name] = dict(size='%dx%d' % (w, d), free=bool(fits(strict, near, w + 2, d + 2)),
                                    reclaim=bool(fits(soft, near, w + 2, d + 2)))
    return out


if __name__ == '__main__':
    res = {}
    only = set(sys.argv[1:])
    for s in range(P['ns']):
        sid = P['sId'][s]
        tier = tier_of(sid)
        if only and sid not in only:
            continue
        if not only and tier not in ('capital', 'city', 'town', 'village', 'hamlet'):
            continue
        res[sid] = settlement_budget(s)
    json.dump(res, open('budget.json', 'w'))
    by = collections.defaultdict(list)
    for sid, r in res.items():
        by[tier_of(sid)].append(r)
    for t, rs in by.items():
        n = len(rs)
        print(t, n, 'median free%%=%.1f' % (100 * np.median([r['free'] / max(1, r['area_circle']) for r in rs])),
              'median soft%%=%.1f' % (100 * np.median([r['soft_works'] / max(1, r['area_circle']) for r in rs])),
              'street%%=%.1f' % (100 * np.median([r['street_cells'] / max(1, r['area_circle']) for r in rs])))
        for name in ('leader_hall', 'inn', 'shop', 'smithy', 'house_rich'):
            f = sum(1 for r in rs if r['fit'].get(name, {}).get('free'))
            c = sum(1 for r in rs if r['fit'].get(name, {}).get('reclaim'))
            print('   ', name, TARGETS[t][name], 'fits free %d/%d, with soft works reclaimed %d/%d' % (f, n, c, n))
