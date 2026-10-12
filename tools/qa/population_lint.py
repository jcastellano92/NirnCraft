"""Population lint: residents vs beds, jobs vs workplaces, idle clusters, per generated settlement.

Reads the files the `/skypop <place id>` server command writes (the housing model run over a place's real generated
plan, see com.skycraft.society.Housing): <run dir>/skycraft-previews/population_<id>.json.  qa.ps1 -Server runs the
command for a sample (whiterun, riverwood, helgen, rorikstead, dragon_bridge, falkreath, old_hroldan) and then this.

Fails (exit 1, `ERR` lines) when
  * more residents sleep in beds than the settlement has resident beds, or two residents share a bed;
  * a resident's bed is not a bed anchor, or a resident has no bed/job (guards on post and prisoners excepted);
  * a job is tied to an anchor that is not a workplace (ghost job);
  * a required sample file is missing.
Warns (`WARN`) about idle clusters: more household residents assigned to one meeting point than it can hold, and
settlements with household residents but no meeting point (they stay indoors).

usage: py -3 -I tools/qa/population_lint.py [dir] [--require id,id,...] [--table]
"""
import glob
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
DEFAULT_DIR = os.path.join(ROOT, 'mod', 'run', 'skycraft-previews')
BED_TYPES = {'bed', 'guild_bed', 'guest_bed'}
WORKPLACE = {'jarl', 'housecarl', 'steward', 'wizard', 'inn', 'priest', 'smith', 'merchant', 'alchemist', 'guard',
             'guild_leader', 'guild_vendor', 'guild_trainer', 'jeweler', 'bookseller', 'tailor', 'butcher', 'fletcher',
             'fence', 'farmer', 'miller', 'fisher', 'hunter', 'miner', 'lumberjack', 'worker', 'bard', 'jail_prisoner'}
MAX_IDLE_PER_MEETING = 8


def lint(path):
    d = json.load(open(path, encoding='utf8'))
    errs, warns = [], []
    anchors = d['anchor_list']
    people = d['people_list']
    sid = d['id']
    cap = d['capacity']
    sleepers = [p for p in people if p['bed'] >= 0]
    if len(sleepers) > cap:
        errs.append('%s: %d residents but only %d resident beds' % (sid, len(sleepers), cap))
    seen = {}
    for p in people:
        slot = p['slot']
        label = '%s #%d (%s)' % (sid, slot, p['job'])
        if p['bed'] >= 0:
            if p['bed'] in seen:
                errs.append('%s: bed %d shared by %s and %s' % (sid, p['bed'], seen[p['bed']], p['job']))
            seen[p['bed']] = p['job']
            if p['bed'] >= len(anchors) or anchors[p['bed']][0] not in BED_TYPES:
                errs.append('%s: bed anchor %d is not a bed' % (label, p['bed']))
        elif anchors[slot][0] not in ('guard', 'jail_prisoner'):
            errs.append('%s: resident without a bed' % label)
        if not p['job']:
            errs.append('%s: resident without a job' % label)
        w = p['work']
        if w >= 0 and (w >= len(anchors) or anchors[w][0] not in WORKPLACE):
            errs.append('%s: workplace anchor %d is %s (ghost job)' % (label, w, anchors[w][0] if w < len(anchors) else '?'))
    for m, n in d['idle_at'].items():
        if n > MAX_IDLE_PER_MEETING:
            warns.append('%s: %d idlers assigned to meeting point #%s (max %d)' % (sid, n, m, MAX_IDLE_PER_MEETING))
    if d['household_idle'] and not d['meetings']:
        warns.append('%s: %d household residents but no meeting point (they stay indoors)' % (sid, d['household_idle']))
    return d, errs, warns


def main(argv):
    folder = DEFAULT_DIR
    require = []
    table = False
    args = list(argv)
    while args:
        a = args.pop(0)
        if a == '--require':
            require = [x for x in args.pop(0).split(',') if x]
        elif a == '--table':
            table = True
        else:
            folder = a
    files = sorted(glob.glob(os.path.join(folder, 'population_*.json')))
    have = {os.path.basename(f)[len('population_'):-len('.json')] for f in files}
    bad = 0
    rows = []
    for rid in require:
        if rid not in have:
            print('ERR  population_%s.json missing (the /skypop command did not run for it)' % rid)
            bad += 1
    for f in files:
        d, errs, warns = lint(f)
        for e in errs:
            print('ERR  ' + e)
        for w in warns:
            print('WARN ' + w)
        bad += len(errs)
        rows.append(d)
    if table or not bad:
        print('%-16s %5s %5s %6s %6s %5s %5s %6s' % ('settlement', 'tier', 'beds', 'resid', 'post', 'inmat', 'old', 'unstf'))
        for d in rows:
            print('%-16s %5d %5d %6d %6d %5d %5d %6d' % (d['id'], d['tier'], d['capacity'], d['residents'], d['post_sleepers'],
                                                       d['inmates'], d['legacy'], len(d['unstaffed'])))
    print('population lint: %d settlements, %d errors' % (len(rows), bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
