#!/usr/bin/env python3
"""Placement lint: does every scatter kind, landmark kind and minor site have a world reason?

Reports kinds missing `purpose` / `needs` / `clue`, kinds drawn in a zone outside their `needs`, isolated
single-prop kinds (fewer than three related props, no builder/cluster/anchor), expected props per chunk, and the
per-zone / per-biome-family kind mix. Exit code 1 if anything required is missing.

    py -3 -I tools/qa/placement_lint.py [--mix]
"""
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RES = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'skycraft')
JAVA = os.path.join(ROOT, 'mod', 'src', 'main', 'java', 'com', 'skycraft')


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

    print('PROBLEMS: %d' % problems)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
