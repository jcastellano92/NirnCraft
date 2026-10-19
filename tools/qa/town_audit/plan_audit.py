import sys, os, json, glob, math, collections, statistics
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from explore import *   # P, spec, ARCH, USE

YARD = ["CART", "WOODPILE", "CRATES", "HAY", "LAUNDRY", "BENCH", "LAMP_POST", "BRAZIER", "NOTICE_BOARD", "WELL", "FOUNTAIN",
        "STATUE", "FLOWER_BED", "VENDOR", "DUMMY", "COOP", "TREE", "SIGNPOST", "BARRELS", "KENNEL", "SHRINE", "TROUGH", "POND",
        "REFLECTING_POOL", "MEMORIAL", "BELL_TOWER", "BANNER", "GREAT_TREE", "WATERWHEEL", "AQUEDUCT", "BASIN", "STATUE_THEMED"]
F_OMIT = 8192; F_SEAT = 1024; F_RUINED = 256; F_UPPER = 2; F_GROUND = 2048; F_RICH = 4; F_FOR_SALE = 1
F_RESERVED = 4096; F_NOBACK = 16384; F_BURNT = 32768; F_MAJOR = 64

PL = {}
base = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'mod', 'src', 'main', 'resources', 'nirncraft', 'tamriel', 'places') + os.sep
for f in glob.glob(base + '*.json'):
    n = os.path.basename(f).replace('.json', '')
    if n in ('index', 'realms'):
        continue
    d = json.load(open(f, encoding='utf8'))
    for p in d:
        if isinstance(p, dict) and 'id' in p:
            p = dict(p)
            p['file'] = n
            PL[p['id']] = p
SKY_CAPITALS = {'whiterun', 'solitude', 'windhelm', 'riften', 'markarth'}


def tier_of(sid):
    p = PL.get(sid)
    if not p:
        return 'unknown'
    t = p.get('type')
    if t == 'capital':
        if p['file'] == 'skyrim_settlements':
            return 'capital' if sid in SKY_CAPITALS else 'city'
        return 'capital'
    return {'city': 'city', 'town': 'town', 'village': 'village', 'farm': 'hamlet'}.get(t, t)


FLOORS = {'capital': dict(interiors=27, named=23, merchants=15, inns=1), 'city': dict(interiors=10, named=11, merchants=4, inns=1),
          'town': dict(interiors=4.5, named=3.5, merchants=1, inns=0.5), 'village': dict(interiors=2, named=1, merchants=0, inns=0),
          'hamlet': dict(interiors=1, named=0, merchants=0, inns=0)}


DIR_STEP = [(0, 1), (-1, 0), (0, -1), (1, 0)]


def mapx(f, lx, lz):
    return {3: -lz, 0: -lx, 1: lz}.get(f, lx)


def mapz(f, lx, lz):
    return {3: lx, 0: -lz, 1: -lx}.get(f, lz)


def rect(sp, g=0):
    w, d, f = sp['w'], sp['d'], sp['facing']
    lcx, lcz = (w - 1) // 2, (d - 1) // 2
    ox, oz = sp['x'] - mapx(f, lcx, lcz), sp['z'] - mapz(f, lcx, lcz)
    ax, az = ox + mapx(f, 0, 0), oz + mapz(f, 0, 0)
    bx, bz = ox + mapx(f, w - 1, d - 1), oz + mapz(f, w - 1, d - 1)
    return (min(ax, bx) - g, min(az, bz) - g, max(ax, bx) + g, max(az, bz) + g)


BUILDING_KINDS = {'HOUSE', 'MANOR', 'LONGHOUSE', 'INN', 'SMITHY', 'STORE', 'ALCHEMIST', 'TEMPLE', 'SHRINE', 'PALACE', 'BARRACKS',
                  'STABLES', 'FARMHOUSE', 'MILL', 'HALL_OF_DEAD', 'TOWER', 'TENT', 'GUILD_HALL', 'JAIL', 'BUILDING', 'RESERVED'}
PROP_KINDS = {'YARD', 'LAMP', 'WELL', 'STALL'}


LM_LEADER = {5, 7, 8, 13, 14, 16, 17}   # COLLEGE, WHITE_GOLD_TOWER, GIANT_TREE, CASTLE, CLIFF, BLUE_PALACE, DOME_PALACE
LM_TEMPLE = {10, 11}                    # DOME_TEMPLE, XANMEER
LM_MAGE = {12}                          # CRYSTAL_TOWER


def is_building(sp):
    if sp['kind'] in BUILDING_KINDS:
        return True
    return sp['kind'] == 'LANDMARK' and not sp['flags'] & F_GROUND and sp['variant'] in (LM_LEADER | LM_TEMPLE | LM_MAGE)


def klass(sp):
    k, u, fl = sp['kind'], sp['use'], sp['flags']
    if k == 'LANDMARK':
        v = sp['variant']
        return 'leader_hall' if v in LM_LEADER else 'temple' if v in LM_TEMPLE else 'mage_tower' if v in LM_MAGE else 'other_building'
    if fl & F_RESERVED:
        return 'player_home'
    if k == 'PALACE' or u == 'KEEP' or (fl & F_SEAT and k in ('LONGHOUSE', 'MANOR', 'BUILDING', 'HOUSE')):
        return 'leader_hall'
    if k == 'INN' or u == 'INN':
        return 'inn'
    if k == 'TEMPLE' or u == 'TEMPLE' or k == 'SHRINE':
        return 'temple'
    if k == 'GUILD_HALL' or u == 'GUILD_HALL':
        return 'guild_hall'
    if k == 'JAIL':
        return 'jail'
    if k == 'BARRACKS' or u in ('BARRACKS', 'GUARDHOUSE'):
        return 'garrison'
    if k == 'HALL_OF_DEAD' or u == 'HALL_OF_DEAD':
        return 'hall_of_dead'
    if k == 'SMITHY' or u == 'SMITHY':
        return 'shop_smith'
    if k == 'ALCHEMIST' or u == 'ALCHEMIST':
        return 'shop_apothecary'
    if k == 'STORE' or u == 'GENERAL_STORE':
        return 'shop_general'
    if u in ('JEWELLER', 'BOOKSELLER', 'TAILOR', 'BUTCHER', 'FLETCHER', 'TANNERY'):
        return 'shop_' + u.lower()
    if u == 'MAGE_TOWER':
        return 'mage_tower'
    if u in ('MILL', 'SAWMILL', 'SMELTER', 'FISHERY', 'WAREHOUSE', 'BARN') or k == 'MILL':
        return 'workshop'
    if k == 'STABLES' or u == 'STABLES':
        return 'stables'
    if k == 'MANOR' or u == 'MANOR':
        return 'house_manor'
    if k in ('HOUSE', 'FARMHOUSE') or u in ('HOVEL', 'COTTAGE', 'HOUSE', 'TOWNHOUSE', 'FARMHOUSE', 'SHACK'):
        return 'house'
    if k == 'TOWER':
        return 'tower'
    return 'other_building'


KEY = ('leader_hall', 'inn', 'temple', 'guild_hall', 'jail', 'garrison', 'shop_smith', 'shop_apothecary', 'shop_general',
       'shop_jeweller', 'shop_bookseller', 'shop_tailor', 'shop_butcher', 'shop_fletcher', 'shop_tannery', 'hall_of_dead',
       'stables', 'mage_tower', 'house_manor', 'workshop')
SHOPS = ('shop_smith', 'shop_apothecary', 'shop_general', 'shop_jeweller', 'shop_bookseller', 'shop_tailor', 'shop_butcher',
         'shop_fletcher', 'shop_tannery')
# job anchors the building interior emits (from the interiors/Programs audit) per key class; guild halls (grammar) emit none
PROVIDES = {'leader_hall': ['JARL', 'HOUSECARL', 'STEWARD', 'WIZARD'], 'inn': ['INN', 'BARD', 'INN_BED'], 'temple': ['PRIEST'],
            'guild_hall': [], 'jail': ['JAIL_GUARD'], 'garrison': ['GUARD'], 'shop_smith': ['SMITH'],
            'shop_apothecary': ['ALCHEMIST'], 'shop_general': ['MERCHANT'], 'shop_jeweller': ['JEWELER'],
            'shop_bookseller': ['BOOKSELLER'], 'shop_tailor': ['TAILOR'], 'shop_butcher': ['BUTCHER'],
            'shop_fletcher': ['FLETCHER'], 'shop_tannery': ['HUNTER'], 'stables': ['STABLES']}
# anchors a working building needs for TO4 (counter/forge/throne/desk/bed), data names as in AnchorType where they exist
NEEDS = {'leader_hall': ['JARL', 'STEWARD', 'HOUSECARL', 'WIZARD', 'SEAT_THRONE_SIT', 'DESK'], 'inn': ['INN', 'BARD', 'INN_BED', 'DESK'],
         'temple': ['PRIEST'], 'guild_hall': ['GUILD_LEADER', 'GUILD_TRAINER', 'GUILD_VENDOR'], 'jail': ['JAIL_GUARD'],
         'shop_smith': ['SMITH', 'COUNTER'], 'shop_apothecary': ['ALCHEMIST', 'COUNTER'], 'shop_general': ['MERCHANT', 'COUNTER']}


def street_cells(s):
    """cells painted as streets: STREET/BRIDGE spec bands (value 1) and doorstep PATH edges (value 2)"""
    cells = {}
    for i in range(P['sSpecOff'][s], P['sSpecOff'][s + 1]):
        sp = spec(i)
        if sp['kind'] not in ('STREET', 'BRIDGE') or (sp['x'] == sp['x2'] and sp['z'] == sp['z2']):
            continue
        ax, az, bx, bz = sp['x'] + 0.5, sp['z'] + 0.5, sp['x2'] + 0.5, sp['z2'] + 0.5
        half = max(1, sp['w']) / 2.0
        dx, dz = bx - ax, bz - az
        l2 = dx * dx + dz * dz
        for x in range(int(math.floor(min(ax, bx) - half - 1)), int(math.ceil(max(ax, bx) + half + 1)) + 1):
            for z in range(int(math.floor(min(az, bz) - half - 1)), int(math.ceil(max(az, bz) + half + 1)) + 1):
                px, pz = x + 0.5 - ax, z + 0.5 - az
                t = 0 if l2 < 1e-6 else max(0, min(1, (px * dx + pz * dz) / l2))
                if max(abs(px - t * dx), abs(pz - t * dz)) <= half + 0.01:
                    cells[(x, z)] = 1
    for e in range(P['sEdgeOff'][s], P['sEdgeOff'][s + 1]):
        if P['eClass'][e] != 3:
            continue
        a, b = P['ea'][e], P['eb'][e]
        ax, az, bx, bz = P['nx'][a], P['nz'][a], P['nx'][b], P['nz'][b]
        n = int(max(abs(bx - ax), abs(bz - az)))
        for k in range(n + 1):
            t = 0 if n == 0 else k / n
            c = (int(math.floor(ax + (bx - ax) * t)), int(math.floor(az + (bz - az) * t)))
            cells.setdefault(c, 2)
    return cells


def raster(s, specs):
    """Walkable cell model of the baked plan: street/lane/alley/bridge bands, stairs, doorstep paths, plaza rectangles and
    gate passages -> {(x,z): (h, flag)} flag 1 normal, 2 stairs/ramp cell."""
    cells = {}
    steep = []

    def put(x, z, h, fl):
        k = (x, z)
        old = cells.get(k)
        if old is None or fl > old[1]:
            cells[k] = (h, fl)

    order = []
    for e in range(P['sEdgeOff'][s], P['sEdgeOff'][s + 1]):
        order.append((P['eClass'][e] == 3, e))
    for _, e in sorted(order):
        c = P['eClass'][e]
        a, b = P['ea'][e], P['eb'][e]
        if P['nClass'][a] == 4 or P['nClass'][b] == 4:
            continue   # a plaza centre linked to the nearest street node: virtual, the plaza rectangle is drawn below
        ax, az, bx, bz = P['nx'][a], P['nz'][a], P['nx'][b], P['nz'][b]
        ha, hb = P['nh'][a], P['nh'][b]
        half = max(0.5, P['ew'][e] / 2)
        dx, dz = bx - ax, bz - az
        l2 = dx * dx + dz * dz
        ln = math.sqrt(l2)
        rise = abs(hb - ha)
        fl = 2 if c == 4 or (rise >= 2 and rise > 0.35 * ln) else 1
        if c == 4 and ln > 0 and rise > ln + 0.5:
            steep.append(dict(x=round((ax + bx) / 2), z=round((az + bz) / 2), rise=rise, length=round(ln, 1)))
        x0, x1 = int(math.floor(min(ax, bx) - half - 1)), int(math.ceil(max(ax, bx) + half + 1))
        z0, z1 = int(math.floor(min(az, bz) - half - 1)), int(math.ceil(max(az, bz) + half + 1))
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                px, pz = x + 0.5 - ax, z + 0.5 - az
                t = 0 if l2 < 1e-6 else max(0, min(1, (px * dx + pz * dz) / l2))
                if max(abs(px - t * dx), abs(pz - t * dz)) <= half + 0.01:
                    put(x, z, int(math.floor(ha + (hb - ha) * t + 0.5)), fl)
    for sp in specs:
        if sp['flags'] & F_OMIT or sp['floor'] == -2147483648:
            continue
        if sp['kind'] == 'PLAZA':
            r = rect(sp, 0)
            for x in range(r[0], r[2] + 1):
                for z in range(r[1], r[3] + 1):
                    if (x, z) not in cells:
                        put(x, z, sp['floor'], 1)
        elif sp['kind'] == 'GATE':
            r = rect(sp, 0)
            alongz = sp['facing'] in (0, 2)
            for x in range(r[0], r[2] + 1):
                for z in range(r[1], r[3] + 1):
                    off = abs(x - sp['x']) if alongz else abs(z - sp['z'])
                    if off <= 1 and (x, z) not in cells:
                        put(x, z, sp['floor'], 1)
    return cells, steep


def reach_from(cells, seeds):
    seen = set()
    stack = []
    for sd in seeds:
        if sd in cells and sd not in seen:
            seen.add(sd)
            stack.append(sd)
    while stack:
        x, z = stack.pop()
        h, fl = cells[(x, z)]
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx == 0 and dz == 0:
                    continue
                n = (x + dx, z + dz)
                if n in seen or n not in cells:
                    continue
                h2, fl2 = cells[n]
                lim = 2 if (fl == 2 or fl2 == 2) else 1
                if abs(h2 - h) <= lim:
                    seen.add(n)
                    stack.append(n)
    return seen


def audit(s):
    sid = P['sId'][s]
    n0, n1 = P['sNodeOff'][s], P['sNodeOff'][s + 1]
    e0, e1 = P['sEdgeOff'][s], P['sEdgeOff'][s + 1]
    sp0, sp1 = P['sSpecOff'][s], P['sSpecOff'][s + 1]
    specs = [spec(i) for i in range(sp0, sp1)]
    N = n1 - n0
    par = list(range(N))

    def find(x):
        while par[x] != x:
            par[x] = par[par[x]]
            x = par[x]
        return x

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            par[ra] = rb

    # strict graph connectivity: only the edges of the baked graph (streets, stairs, bridges, gate lanes, door paths)
    for e in range(e0, e1):
        union(P['ea'][e] - n0, P['eb'][e] - n0)
    nx = [P['nx'][n0 + i] for i in range(N)]
    nz = [P['nz'][n0 + i] for i in range(N)]
    nh = [P['nh'][n0 + i] for i in range(N)]
    ncl = [P['nClass'][n0 + i] for i in range(N)]
    step_gaps = []

    def try_merge(a, b, reason, pos):
        if find(a) == find(b):
            return
        if abs(nh[a] - nh[b]) <= 1:
            union(a, b)
        else:
            step_gaps.append(dict(x=round(pos[0]), z=round(pos[1]), h_from=nh[a], h_to=nh[b], kind=reason))

    for i, sp in enumerate(specs):
        if sp['kind'] in ('PLAZA', 'GATE') and not sp['flags'] & F_OMIT:
            g = 1 if sp['kind'] == 'PLAZA' else 2
            r = rect(sp, g)
            inside = [k for k in range(N) if r[0] <= nx[k] <= r[2] + 1 and r[1] <= nz[k] <= r[3] + 1 and ncl[k] != 2]
            for k in inside[1:]:
                try_merge(inside[0], k, 'plaza' if sp['kind'] == 'PLAZA' else 'gate', (nx[k], nz[k]))
    edges = [(P['ea'][e] - n0, P['eb'][e] - n0, P['ew'][e], P['eClass'][e]) for e in range(e0, e1)]
    walk = [k for k in range(N) if ncl[k] != 2]
    for k in walk:
        px, pz = nx[k], nz[k]
        for a, b, w, c in edges:
            if a == k or b == k or c == 3:
                continue
            if find(k) == find(a):
                continue
            ax, az, bx, bz = nx[a], nz[a], nx[b], nz[b]
            if px < min(ax, bx) - w - 2 or px > max(ax, bx) + w + 2 or pz < min(az, bz) - w - 2 or pz > max(az, bz) + w + 2:
                continue
            dx, dz = bx - ax, bz - az
            l2 = dx * dx + dz * dz
            t = 0 if l2 < 1e-6 else max(0, min(1, ((px - ax) * dx + (pz - az) * dz) / l2))
            d = math.hypot(ax + dx * t - px, az + dz * t - pz)
            if d <= w / 2 + 1.0:
                hh = nh[a] + (nh[b] - nh[a]) * t
                if abs(nh[k] - hh) <= 1:
                    union(k, a)
                else:
                    step_gaps.append(dict(x=round(px), z=round(pz), h_from=nh[k], h_to=round(hh, 1), kind='tjunction'))
    gates = [k for k in range(N) if ncl[k] in (1, 5)]
    gate_roots = {find(k) for k in gates}
    comp_sizes = collections.Counter(find(k) for k in range(N))
    cells, steep = raster(s, specs)
    gate_nodes = [k for k in range(N) if ncl[k] == 1] or [k for k in range(N) if ncl[k] == 5]
    uniq = []
    for k in gate_nodes:
        if all(math.hypot(nx[k] - nx[u], nz[k] - nz[u]) > 6 for u in uniq):
            uniq.append(k)
    gate_nodes = uniq[:8]
    seen_sets = []
    for k in gate_nodes:
        cx0, cz0 = int(math.floor(nx[k])), int(math.floor(nz[k]))
        seeds = [(cx0 + dx, cz0 + dz) for dx in range(-2, 3) for dz in range(-2, 3)]
        seen_sets.append(reach_from(cells, seeds))
    doors = []
    for d in range(P['sDoorOff'][s], P['sDoorOff'][s + 1]):
        node = P['dNode'][d] - n0
        ox, oz = DIR_STEP[P['dFacing'][d]]
        out = (P['dx'][d] + ox, P['dz'][d] + oz)
        rg = [i for i, sn in enumerate(seen_sets) if out in sn]
        doors.append(dict(spec=P['dSpec'][d], x=P['dx'][d], y=P['dy'][d], z=P['dz'][d], facing=P['dFacing'][d],
                          plan_reach=P['dReach'][d], gate_reach=bool(rg), all_gates=bool(seen_sets) and len(rg) == len(seen_sets),
                          gates=rg, comp=find(node)))
    bydoor = collections.defaultdict(list)
    for dd in doors:
        bydoor[dd['spec']].append(dd)
    tier = tier_of(sid)
    classes = collections.defaultdict(list)
    keyb = []
    for i, sp in enumerate(specs):
        if not is_building(sp) or sp['flags'] & F_OMIT:
            continue
        k = klass(sp)
        classes[k].append(i)
        if k in KEY:
            ds = bydoor.get(i, [])
            front = [dd for dd in ds if dd['facing'] == sp['facing']] or ds
            if not ds:
                st = 'no_door'
            elif any(dd['gate_reach'] for dd in front):
                st = 'reachable'
            elif any(dd['plan_reach'] for dd in ds):
                st = 'street_only'     # the planner found a street, but it is not connected to any gate
            else:
                st = 'unreachable'
            keyb.append(dict(spec=i, cls=k, kind=sp['kind'], use=sp['use'], name=sp['name'], x=sp['x'], z=sp['z'], w=sp['w'],
                             d=sp['d'], floor=sp['floor'], upper=bool(sp['flags'] & F_UPPER), ruined=bool(sp['flags'] & F_RUINED),
                             doors=len(ds), status=st, reach_gates=st == 'reachable', gates_reaching=sorted({g for dd in front for g in dd['gates']}),
                             job_anchors_expected=PROVIDES.get(k, [])))
    fl = collections.defaultdict(lambda: [0, 0])
    for i, sp in enumerate(specs):
        if is_building(sp) and sp['kind'] != 'TOWER' and not sp['flags'] & F_OMIT and i in bydoor:
            ok = any(dd['gate_reach'] for dd in bydoor[i])
            fl[sp['floor']][0] += 1
            fl[sp['floor']][1] += ok
    levels = [dict(floor=f, buildings=v[0], reachable=v[1]) for f, v in sorted(fl.items()) if v[0] >= 2]
    cells = street_cells(s)
    plaza = set()
    for sp in specs:
        if sp['kind'] == 'PLAZA':
            r = rect(sp, 0)
            for x in range(r[0], r[2] + 1):
                for z in range(r[1], r[3] + 1):
                    plaza.add((x, z))
    props = collections.Counter()
    props_street = []
    props_total = 0
    for i, sp in enumerate(specs):
        if sp['kind'] not in PROP_KINDS or sp['flags'] & F_OMIT:
            continue
        props_total += 1
        r = rect(sp, 0)
        hit = False
        for x in range(r[0], r[2] + 1):
            for z in range(r[1], r[3] + 1):
                if (x, z) in cells and (x, z) not in plaza:
                    hit = True
        if hit:
            nm = YARD[sp['variant'] % 32] if sp['kind'] == 'YARD' else sp['kind']
            props[nm] += 1
            props_street.append(dict(spec=i, kind=nm, x=sp['x'], z=sp['z'], w=sp['w'], d=sp['d'], name=sp['name']))
    shrines = [dict(spec=i, x=sp['x'], z=sp['z'], name=sp['name'], w=sp['w'], on_street=any(
        (x, z) in cells and (x, z) not in plaza for x in range(rect(sp)[0] - 1, rect(sp)[2] + 2) for z in range(rect(sp)[1] - 1, rect(sp)[3] + 2)))
        for i, sp in enumerate(specs) if sp['kind'] == 'YARD' and sp['variant'] % 32 == 20 and not sp['flags'] & F_OMIT]
    cx, cz, reach = P['sX'][s], P['sZ'][s], P['sReach'][s]
    blds = [(sp['x'], sp['z']) for sp in specs if sp['kind'] in BUILDING_KINDS and sp['kind'] not in ('TOWER', 'FARMHOUSE')
            and not sp['flags'] & F_OMIT]
    core_r = {'capital': 0.6, 'city': 0.55, 'town': 0.5, 'village': 0.5, 'hamlet': 0.4}.get(tier, 0.5) * reach
    scat = collections.Counter()
    scat_list = []
    for i, sp in enumerate(specs):
        if sp['flags'] & F_OMIT or sp['kind'] not in ('FIELD', 'GARDEN', 'PEN', 'HOMESTEAD_PLOT'):
            continue
        near = sum(1 for (bx, bz) in blds if abs(bx - sp['x']) <= 20 and abs(bz - sp['z']) <= 20)
        dist = math.hypot(sp['x'] - cx, sp['z'] - cz)
        if dist <= core_r and near >= 4:
            scat[sp['kind']] += 1
            scat_list.append(dict(spec=i, kind=sp['kind'], x=sp['x'], z=sp['z'], w=sp['w'], d=sp['d'], bld_within_20=near, dist=round(dist)))
    hs = [specs[i] for i in classes.get('house', [])]
    sig = collections.Counter((h['w'], h['d']) for h in hs)
    small = sum(1 for h in hs if h['w'] * h['d'] <= 63)
    rep = dict(houses=len(hs), distinct_footprints=len(sig), max_same_footprint=max(sig.values()) if sig else 0,
               footprints={'%dx%d' % k: v for k, v in sig.most_common(8)},
               small_leq_7x9=small, small_pct=round(100 * small / len(hs), 1) if hs else 0)
    areas = {}
    for k, idx in classes.items():
        ww = [specs[i]['w'] for i in idx]
        dd = [specs[i]['d'] for i in idx]
        ia = [(specs[i]['w'] - 2) * (specs[i]['d'] - 2) for i in idx]
        areas[k] = dict(n=len(idx), median_w=statistics.median(ww), median_d=statistics.median(dd),
                        interior_area_1f_median=statistics.median(ia), interior_area_1f_min=min(ia), interior_area_1f_max=max(ia),
                        footprint_median=statistics.median([specs[i]['w'] * specs[i]['d'] for i in idx]),
                        upper=sum(1 for i in idx if specs[i]['flags'] & F_UPPER))
    enterable = [i for i, sp in enumerate(specs) if is_building(sp) and sp['kind'] != 'TOWER' and i in bydoor
                 and not sp['flags'] & F_RESERVED]
    named = [i for i in enterable if specs[i]['name'] and not specs[i]['name'].startswith('#')]
    merch = [i for i in enterable if klass(specs[i]) in SHOPS]
    inns = [i for i in enterable if klass(specs[i]) == 'inn']
    fl_ = FLOORS.get(tier)
    counts = dict(interiors=len(enterable), named=len(named), merchants=len(merch), inns=len(inns))
    meets = {k: (None if not fl_ else counts[k] >= fl_[k]) for k in counts}
    kb_by = collections.defaultdict(list)
    for kb in keyb:
        kb_by[kb['cls']].append(kb)
    # anchors missing: key classes whose program emits no anchor for its job
    anchors_missing = {k: len(v) for k, v in kb_by.items() if k in ('guild_hall',) and v}
    out = dict(id=sid, tier=tier, type=PL.get(sid, {}).get('type'), culture=PL.get(sid, {}).get('culture'),
               region=PL.get(sid, {}).get('region'), x=cx, z=cz, reach=reach, specs=len(specs),
               buildings=sum(len(v) for v in classes.values()),
               gates=[dict(x=round(nx[k], 1), z=round(nz[k], 1), h=nh[k], node=('gate' if ncl[k] == 1 else 'road_join'),
                           comp_nodes=comp_sizes[find(k)]) for k in gates],
               graph=dict(nodes=N, edges=e1 - e0, components=len(comp_sizes), gate_components=len(gate_roots),
                          nodes_in_gate_components=sum(c for r, c in comp_sizes.items() if r in gate_roots)),
               doors=dict(total=len(doors), plan_reachable=sum(1 for d in doors if d['plan_reach']),
                          gate_reachable=sum(1 for d in doors if d['gate_reach'])),
               key_buildings=keyb,
               key_summary={k: dict(n=len(v), reachable=sum(1 for b in v if b['status'] == 'reachable'),
                                    not_reachable=sum(1 for b in v if b['status'] != 'reachable')) for k, v in kb_by.items()},
               tiers=dict(levels=levels, connected=(all(l['reachable'] >= 0.25 * l['buildings'] for l in levels if l['buildings'] >= 3) if levels else None)),
               step_gaps=step_gaps[:30], step_gap_count=len(step_gaps),
               props=dict(total=props_total, on_street=sum(props.values()), by_kind=dict(props), items=props_street[:30]),
               shrines=dict(count=len(shrines), w1=sum(1 for x in shrines if x['w'] == 1),
                            on_street=sum(1 for x in shrines if x['on_street']), items=shrines[:12]),
               scatter_core=dict(count=sum(scat.values()), by_kind=dict(scat), items=scat_list[:20], core_radius=round(core_r)),
               repeats=rep, areas=areas, counts_vs_floor=dict(counts=counts, floor=fl_, meets=meets),
               walk=dict(cells=len(cells), gates_used=len(gate_nodes), steep_stairs=steep[:10], steep_stairs_n=len(steep),
                         reached_cells=[len(sn) for sn in seen_sets]),
               anchors_missing=anchors_missing)
    return out


if __name__ == '__main__':
    only = sys.argv[1:]
    res = []
    for s in range(P['ns']):
        if only and P['sId'][s] not in only:
            continue
        res.append(audit(s))
    json.dump(res, open('audit_out.json' if only else 'audit_all.json', 'w'))
    print(len(res))
