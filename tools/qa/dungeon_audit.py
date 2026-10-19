"""Offline dungeon audit over exports (no world, no client): renders every export of the sample list and builds one
contact sheet per dungeon plus the reachability report.

  1. boot once:  tools\\qa\\qa.ps1 -Server -NoBuild -ServerCmds "nirncraft-dungeon-qa export <ids from dungeon_sample.json>"
  2. py -3 -I tools/qa/dungeon_audit.py [--exports mod/run/qa-exports] [--out tools/qa/out/dungeon_audit]

Writes <out>/<id>/*.png (render_dungeon.py), <out>/<id>.jpg (contact sheet, <= 1600 px wide), <out>/reach.md and
reach.json (dungeon_reach.py).
"""
import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
PY = [sys.executable, '-I']


def pick_tiles(d):
    """The few images worth reviewing: exterior, structure only, the biggest levels, one interior."""
    files = sorted(os.listdir(d))
    want = []
    for pre in ('00_exterior_iso.png', '02_structure_only_SE.png'):
        if pre in files:
            want.append(pre)
    lv = [f for f in files if f.startswith('1') and '_level_' in f]
    lv.sort(key=lambda f: -os.path.getsize(os.path.join(d, f)))
    want += sorted(lv[:3])
    it = [f for f in files if f.startswith('2') and '_interior_' in f]
    want += it[:1]
    want += [f for f in files if f.endswith(('_cavern0_half.png', '_cavern0_plan.png'))]
    return want


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--exports', default=os.path.join(ROOT, 'mod', 'run', 'qa-exports'))
    ap.add_argument('--out', default=os.path.join(ROOT, 'tools', 'qa', 'out', 'dungeon_audit'))
    ap.add_argument('--sample', default=os.path.join(HERE, 'dungeon_sample.json'))
    ap.add_argument('--only', nargs='*')
    ap.add_argument('--extra', nargs='*', default=[], help='more export ids beyond the sample list')
    a = ap.parse_args()
    ids = json.load(open(a.sample, encoding='utf8'))['ids']
    if a.only:
        ids = [i for i in ids if i in a.only]
    ids = ids + [i for i in a.extra if i not in ids]
    os.makedirs(a.out, exist_ok=True)
    done = []
    for i in ids:
        src = os.path.join(a.exports, i + '.json.gz')
        if not os.path.exists(src):
            print('MISSING export', i)
            continue
        od = os.path.join(a.out, i)
        os.makedirs(od, exist_ok=True)
        r = subprocess.run(PY + [os.path.join(HERE, 'render_dungeon.py'), src, od, '--name', i], capture_output=True, text=True)
        if r.returncode != 0:
            print('render failed', i, r.stderr[-400:])
            continue
        # the grand caverns (if any): a half cutaway and a plan, which join the sheet
        subprocess.run(PY + [os.path.join(HERE, 'dungeon_cavern_view.py'), src, od, '--max', '1'], capture_output=True, text=True)
        tiles = pick_tiles(od)
        tmp = tempfile.mkdtemp()
        for k, f in enumerate(tiles):
            shutil.copy(os.path.join(od, f), os.path.join(tmp, '%02d_%s' % (k, f)))
        sheet = os.path.join(a.out, i + '.jpg')
        subprocess.run(PY + [os.path.join(HERE, 'contact_sheet.py'), tmp, sheet, '2', '800'], check=False)
        shutil.rmtree(tmp, ignore_errors=True)
        done.append(i)
        print('rendered', i, len(tiles), 'tiles')
    files = [os.path.join(a.exports, i + '.json.gz') for i in done]
    subprocess.run(PY + [os.path.join(HERE, 'dungeon_reach.py')] + files +
                   ['--md', os.path.join(a.out, 'reach.md'), '--json', os.path.join(a.out, 'reach.json')], check=False)


if __name__ == '__main__':
    main()
