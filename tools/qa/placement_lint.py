#!/usr/bin/env python3
"""Placement lint: does every scatter kind, landmark kind and minor site have a world reason?

Reports kinds missing `purpose` / `needs` / `clue`, kinds drawn in a zone outside their `needs`, isolated
single-prop kinds (fewer than three related props, no builder/cluster/anchor), expected props per chunk, and the
per-zone / per-biome-family kind mix. Exit code 1 if anything required is missing.

Pipeline P6 (planned scenes, docs/WORLDGEN-PIPELINE.md section 8): every human prop belongs to a scene of the baked
scene plan (mod/src/main/resources/nirncraft/baked/sceneplan.bin). Checks that no zone of rules.json rolls a human kind,
that the code guards are in place (no per-chunk human roll, no wayside pass, landmark grid without human vignettes,
road dressing off in plan-era worlds), that every scene has an anchor, a reason and props of human kinds only, and
reports the scene density per km of road (by class, from roadplan.bin) and per km² of land against the targets.

    py -3 -I tools/qa/placement_lint.py [--mix]
"""
import json
import os
import re
import struct
import sys
import zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RES = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'nirncraft')
JAVA = os.path.join(ROOT, 'mod', 'src', 'main', 'java', 'com', 'nirncraft')


def load(p):
    with open(p, encoding='utf-8') as f:
        return json.load(f)


def prop_count(variant):
    n = 0
    for e in variant:
        if 'fill' in e:
            f = e['fill']
            n += (abs(f[3] - f[0]) + 1) * (abs(f[4] - f[1]) + 1) * (abs(f[5] - f[2]) + 1)
        else:
            n += 1
    return n


def main():
    show_mix = '--mix' in sys.argv
    problems = 0
    kinds = load(os.path.join(RES, 'scatter', 'kinds.json'))
    rules = load(os.path.join(RES, 'scatter', 'rules.json'))
    kinds = {k: v for k, v in kinds.items() if not k.startswith('_')}

    used = {}
    for z, groups in rules['zones'].items():
        for g in groups:
            for k in g['kinds']:
                used.setdefault(k, set()).add(z)
    for k in rules.get('wayside', {}).get('kinds', {}):
        used.setdefault(k, set()).add('wayside')

    print('== scatter kinds: %d ==' % len(kinds))
    miss_p = [k for k, v in kinds.items() if not v.get('purpose')]
    miss_n = [k for k, v in kinds.items() if not v.get('needs')]
    miss_c = [k for k, v in kinds.items() if not v.get('clue')]
    print('missing purpose: %d %s' % (len(miss_p), miss_p[:8]))
    print('missing needs:   %d %s' % (len(miss_n), miss_n[:8]))
    print('missing clue:    %d %s' % (len(miss_c), miss_c[:8]))
    problems += len(miss_p) + len(miss_n)
    out_of_context = []
    for k, zs in used.items():
        needs = set(kinds.get(k, {}).get('needs', []))
        for z in zs:
            if z not in needs:
                out_of_context.append('%s in %s' % (k, z))
    print('drawn outside needs: %d %s' % (len(out_of_context), out_of_context[:6]))
    problems += len(out_of_context)

    isolated = []
    for k, v in kinds.items():
        if v.get('builder') or v.get('cluster') or v.get('anchor'):
            continue
        most = max((prop_count(x) for x in v.get('variants', [])), default=0)
        if most < 3:
            isolated.append(k)
    print('isolated single-prop kinds: %d %s' % (len(isolated), isolated))
    unused = [k for k in kinds if k not in used]
    print('kinds not drawn by any zone (builders/explicit callers only): %d' % len(unused))

    caps = rules.get('caps', {})
    dens = sum(g['density'] for gs in rules['zones'].values() for g in gs) / max(1, len(rules['zones']))
    print('caps: %s ; mean zone density (props/chunk before scaling) %.2f' % (caps, dens))

    if show_mix:
        print('-- per-zone kind mix (weight share) --')
        for z, groups in rules['zones'].items():
            tot = {}
            for g in groups:
                s = sum(g['kinds'].values()) or 1
                for k, w in g['kinds'].items():
                    tot[k] = tot.get(k, 0) + g['density'] * w / s
            allp = sum(tot.values()) or 1
            top = sorted(tot.items(), key=lambda kv: -kv[1])[:6]
            print('  %-17s %.2f props/chunk: %s' % (z, allp, ', '.join('%s %.0f%%' % (k, 100 * v / allp) for k, v in top)))

    # landmarks
    lm = load(os.path.join(RES, 'landmarks', 'kinds.json'))['kinds']
    lp = [k['id'] for k in lm if not k.get('purpose')]
    lc = [k['id'] for k in lm if not k.get('context')]
    lk = [k['id'] for k in lm if not k.get('clue')]
    print('== landmark kinds: %d == missing purpose %d, context %d, clue %d' % (len(lm), len(lp), len(lc), len(lk)))
    problems += len(lp) + len(lc)
    if show_mix:
        fam = {}
        for k in lm:
            for f, w in k['w'].items():
                fam.setdefault(f, {}).setdefault(k['tier'], 0)
                fam[f][k['tier']] += 1
        for f, t in sorted(fam.items()):
            print('  %-9s %s' % (f, ' '.join('%s:%d' % (a, b) for a, b in sorted(t.items()))))

    # explore minor sites: SiteKind constants must carry a purpose string
    src = open(os.path.join(JAVA, 'explore', 'SiteKind.java'), encoding='utf-8').read()
    consts = re.findall(r'^\s+([A-Z_]+)\((\d+), (true|false)(?:, "([^"]+)")?\)[,;]', src, flags=re.M)
    sp = [c[0] for c in consts if not c[3]]
    print('== minor sites: %d == missing purpose %d %s' % (len(consts), len(sp), sp[:6]))
    problems += len(sp)

    problems += scenes(kinds, rules, lm)

    print('PROBLEMS: %d' % problems)
    return 1 if problems else 0


# ---------------------------------------------------------------------------------------------------- P6 scenes

ANCHORS = ['ROAD_BEND', 'CROSSROADS', 'FORD', 'BRIDGE_END', 'FARM', 'CAMP_SITE', 'SHRINE_SITE', 'BATTLE_SITE', 'RUIN_EDGE',
           'DUNGEON_MOUTH', 'LOREWEB_CLUE', 'SETTLEMENT_EDGE']
ROAD_ANCHORS = {0, 1, 2, 3}
SCENE_MAGIC, SCENE_VERSION = 0x53434e50, 1
# waysides per km of road by class (highway+road, lane, trail) and scenes per km² of land
TARGETS = {'main': (0.33, 0.67), 'lane': (0.25, 0.71), 'trail': (0.17, 0.29), 'land': (5.0, 15.0)}
LM_HUMAN = {'grave', 'cairn_token', 'bedroll', 'offering', 'pack', 'warning', 'remains', 'fire_ring', 'charms', 'pilgrim',
            'hunters', 'travellers', 'smuggler', 'ambush', 'skirmish', 'boat_wreck', 'sacrifice', 'blind', 'claim', 'dig',
            'beacon'}


class _Reader:
    def __init__(self, data):
        self.d, self.p = data, 0

    def take(self, fmt):
        v = struct.unpack_from('>' + fmt, self.d, self.p)
        self.p += struct.calcsize('>' + fmt)
        return v if len(v) > 1 else v[0]

    def utf(self):
        n = self.take('H')
        s = self.d[self.p:self.p + n].decode('utf-8', 'replace')
        self.p += n
        return s


def load_sceneplan(path):
    raw = open(path, 'rb').read()
    n = struct.unpack_from('>H', raw, 0)[0]
    r = _Reader(zlib.decompress(raw[2 + n:]))
    if r.take('i') != SCENE_MAGIC:
        raise SystemExit('not a scene plan: ' + path)
    v = r.take('i')
    if v != SCENE_VERSION:
        raise SystemExit('scene plan version %d, this lint reads %d' % (v, SCENE_VERSION))
    land = r.take('f')
    road_km = [r.take('f') for _ in range(r.take('B'))]
    kinds = [r.utf() for _ in range(r.take('i'))]
    scenes = []
    for _ in range(r.take('i')):
        s = {'kind': kinds[r.take('H')], 'x': r.take('i'), 'z': r.take('i'), 'h': r.take('h') / 16, 'facing': r.take('b'),
             'anchor': r.take('b'), 'ref': r.utf(), 'reason': r.utf(), 'clue': r.utf(), 'note': r.utf(),
             'px': r.take('i'), 'pz': r.take('i'), 'radius': r.take('h'), 'props': []}
        for _ in range(r.take('B')):
            s['props'].append((kinds[r.take('H')], r.take('b'), r.take('b')))
        scenes.append(s)
    return {'land': land, 'road_km': road_km, 'scenes': scenes}


def _src(rel):
    p = os.path.join(JAVA, *rel.split('/'))
    return open(p, encoding='utf-8').read() if os.path.exists(p) else ''


def scenes(kinds, rules, lm):
    """The P6 gate. Returns the number of problems."""
    problems = 0
    human = {k for k, v in kinds.items() if 'human' in v.get('tags', [])}
    print('== P6 planned scenes ==')
    # 1. no per-chunk human rolls: data
    rolled = sorted({k for gs in rules['zones'].values() for g in gs for k in g['kinds'] if k in human})
    print('human kinds rolled per chunk by a zone: %d %s (must be 0)' % (len(rolled), rolled[:8]))
    problems += len(rolled)
    if 'wayside' in rules:
        print('rules.json still has a per-chunk "wayside" table (must be gone)')
        problems += 1
    # 2. ... and code
    guards = [
        ('scatter/Scatter.java', r'if \(k\.human\) return false', 'per-chunk roll skips human kinds'),
        ('scatter/ScatterRules.java', r'if \(k\.human\) \{', 'rule loader refuses human kinds in zones'),
        ('landmarks/Landmarks.java', r'SceneKinds\.planned\(kind\.builder\(\)\)', 'landmark grid leaves human vignettes to scenes'),
        ('roads/Dressing.java', r'ScenePlan\.active\(\)', 'road dressing off in plan-era worlds'),
        ('scatter/ScatterFeature.java', r'SceneRealiser\.generate', 'chunk feature realises the scene plan'),
    ]
    missing = [why for f, pat, why in guards if not re.search(pat, _src(f))]
    gone = os.path.exists(os.path.join(JAVA, 'scatter', 'Waysides.java'))
    print('code guards: %d/%d in place%s%s' % (len(guards) - len(missing), len(guards),
                                               (' MISSING: ' + '; '.join(missing)) if missing else '',
                                               '; Waysides.java (per-chunk wayside pass) still exists' if gone else ''))
    problems += len(missing) + (1 if gone else 0)
    # 3. kinds the scenes use
    sc = rules.get('scenes', {})
    used = set()
    for t in sc.get('anchors', {}).values():
        for k in t:
            used.add(k)
            used.update(kinds.get(k, {}).get('cluster', {}).get('members', []))
    for row in sc.get('clueWords', []):
        used.add(row[1])
    used.add('cairn')
    bad_anchor_kinds = [k for t in sc.get('anchors', {}).values() for k in t
                        if not (k in human or (k.startswith('lm:') and k[3:] in LM_HUMAN))]
    print('anchor tables: %d anchors, non-human or unknown kinds %d %s' % (len(sc.get('anchors', {})), len(bad_anchor_kinds), bad_anchor_kinds[:6]))
    problems += len(bad_anchor_kinds)
    unused = sorted(human - used)
    print('human kinds no scene can place: %d %s' % (len(unused), unused))
    lm_ids = {k['id']: k['builder'] for k in lm}
    # 4. the baked plan
    path = os.path.join(RES, 'baked', 'sceneplan.bin')
    if not os.path.exists(path):
        print('no baked scene plan (run tools/bake/bake.ps1)')
        return problems + 1
    plan = load_sceneplan(path)
    ss = plan['scenes']
    nprops = sum(len(s['props']) for s in ss)
    per = [0] * len(ANCHORS)
    errs = []
    for i, s in enumerate(ss):
        if not 0 <= s['anchor'] < len(ANCHORS):
            errs.append('scene %d: bad anchor %d' % (i, s['anchor']))
            continue
        per[s['anchor']] += 1
        if not s['ref'] or not s['reason']:
            errs.append('scene %d: no anchor ref or reason' % i)
        real = [p for p in s['props'] if p[0] not in ('clue_sign', 'clue_note')]
        if not real:
            errs.append('scene %d (%s): no props' % (i, s['kind']))
        for k, _, _ in real:
            # a scene may hold natural members too (the bones of a battle, a camp's bone pile), but nothing unknown
            ok = k in kinds or (k.startswith('lm:') and lm_ids.get(k[3:]) in LM_HUMAN)
            if not ok:
                errs.append('scene %d: prop %s is not a scatter kind or a human landmark vignette' % (i, k))
        if s['anchor'] == 10 and not s['clue']:
            errs.append('scene %d: lore-web clue without text' % i)
    print('scene plan: %d scenes, %d props (%d landmark vignettes, %d clue signs, %d notes), every prop in a scene by '
          'construction; %d errors %s' % (len(ss), nprops, sum(1 for s in ss for p in s['props'] if p[0].startswith('lm:')),
                                          sum(1 for s in ss for p in s['props'] if p[0] == 'clue_sign'),
                                          sum(1 for s in ss for p in s['props'] if p[0] == 'clue_note'), len(errs), errs[:5]))
    problems += len(errs)
    print('per anchor: ' + ' '.join('%s=%d' % (ANCHORS[a], per[a]) for a in range(len(ANCHORS))))
    # 5. density
    seg_kind = None
    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import roadplan_lint
        rp = roadplan_lint.load(roadplan_lint.DEFAULT)
        seg_kind = [s['kind'] for s in rp['segs']]
    except (SystemExit, Exception) as e:  # noqa: BLE001 (report and go on)
        print('road plan unreadable (%s): waysides per class not split' % e)
    by_class = [0, 0, 0, 0]
    for s in ss:
        if s['anchor'] in ROAD_ANCHORS and s['ref'].startswith('seg:') and seg_kind:
            seg = int(s['ref'].split(':')[1])
            if 0 <= seg < len(seg_kind):
                by_class[max(0, min(3, seg_kind[seg]))] += 1
    km = plan['road_km'] + [0] * (4 - len(plan['road_km']))
    rows = [('main', by_class[0] + by_class[1], km[0] + km[1]), ('lane', by_class[2], km[2]), ('trail', by_class[3], km[3])]
    for name, n, k in rows:
        d = n / k if k > 0 else 0
        lo, hi = TARGETS[name]
        flag = '' if lo <= d <= hi else '  OUT OF TARGET'
        print('waysides per km, %-5s %.2f (%d over %.0f km; target %.2f-%.2f)%s' % (name, d, n, k, lo, hi, flag))
        if flag and seg_kind:
            problems += 1
    d = len(ss) / plan['land'] if plan['land'] > 0 else 0
    lo, hi = TARGETS['land']
    flag = '' if lo <= d <= hi else '  OUT OF TARGET'
    print('scenes per km2 of land %.2f (%d over %.0f km2; target %.0f-%.0f)%s' % (d, len(ss), plan['land'], lo, hi, flag))
    if flag:
        problems += 1
    return problems


if __name__ == '__main__':
    sys.exit(main())
