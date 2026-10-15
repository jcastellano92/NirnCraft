"""Population lint: residents vs beds, jobs vs workplaces, trainers, courts (F-COURT), and idle clusters per settlement.

Reads the files the `/skypop <place id>` server command writes (the housing model run over a place's real generated
plan, see com.skycraft.society.Housing): <run dir>/skycraft-previews/population_<id>.json, together with
mod/src/main/resources/skycraft/society/trainers.json and mod/src/main/resources/skycraft/society/courts.json.
qa.ps1 -Server runs the command for a sample (whiterun, riverwood, helgen, rorikstead, dragon_bridge, falkreath,
old_hroldan) and then this.

Fails (exit 1, `ERR` lines) when
  * more residents sleep in beds than the settlement has resident beds, or two residents share a bed;
  * a resident's bed is not a bed anchor, or a resident has no bed/job (guards on post and prisoners excepted);
  * a job is tied to an anchor that is not a workplace (ghost job);
  * a required sample file is missing;
  * the guard watch breaks the rule (major: 2 posted, minor: 1 patrol, none: 0; no guard residents but Helgen's);
  * any Skyrim capital has < 5 trainers or lacks a court (< 3 court roles);
  * any Skyrim city has < 2 trainers or lacks a court (< 3 court roles).
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
TRAINERS_JSON = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'skycraft', 'society', 'trainers.json')
COURTS_JSON = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'skycraft', 'society', 'courts.json')
SKYRIM_PLACES_JSON = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'skycraft', 'tamriel', 'places', 'skyrim_settlements.json')

BED_TYPES = {'bed', 'guild_bed', 'guest_bed'}
WORKPLACE = {'jarl', 'housecarl', 'steward', 'wizard', 'inn', 'priest', 'smith', 'merchant', 'alchemist', 'guard',
             'guild_leader', 'guild_vendor', 'guild_trainer', 'jeweler', 'bookseller', 'tailor', 'butcher', 'fletcher',
             'fence', 'farmer', 'miller', 'fisher', 'hunter', 'miner', 'lumberjack', 'worker', 'bard', 'jail_prisoner'}
COURT_ROLES = ('jarl', 'steward', 'court_wizard', 'housecarl')
SKYRIM_WALLED_CAPITALS = {'whiterun', 'solitude', 'windhelm', 'riften', 'markarth'}
SKYRIM_HOLD_CITIES = {'falkreath', 'morthal', 'dawnstar', 'winterhold', 'raven_rock'}
MAX_IDLE_PER_MEETING = 8


def load_json_safe(path):
    if not os.path.isfile(path):
        return {}
    with open(path, encoding='utf8') as f:
        return json.load(f)


TRAINERS_DATA = load_json_safe(TRAINERS_JSON)
COURTS_DATA = load_json_safe(COURTS_JSON)
SKYRIM_PLACES_RAW = load_json_safe(SKYRIM_PLACES_JSON) if os.path.isfile(SKYRIM_PLACES_JSON) else []
SKYRIM_PLACES = {p['id']: p for p in SKYRIM_PLACES_RAW if isinstance(p, dict) and 'id' in p}


def resolve_settlement_kind(sid, tier):
    """Returns 'capital', 'city', 'town', or 'village'."""
    if sid in SKYRIM_WALLED_CAPITALS:
        return 'capital'
    if sid in SKYRIM_HOLD_CITIES:
        return 'city'
    place = SKYRIM_PLACES.get(sid)
    if place:
        ptype = place.get('type', '')
        if ptype == 'capital':
            return 'capital'
        if ptype == 'city':
            return 'city'
        if ptype == 'town':
            return 'town'
        if ptype in ('village', 'hamlet', 'settlement'):
            return 'village'
    if tier == 0:
        return 'capital'
    if tier == 1:
        return 'town'
    return 'village'


def is_skyrim_settlement(sid):
    if sid in SKYRIM_PLACES or sid in SKYRIM_WALLED_CAPITALS or sid in SKYRIM_HOLD_CITIES:
        return True
    if sid in TRAINERS_DATA.get('settlements', {}) or sid in COURTS_DATA.get('settlements', {}):
        return True
    return False


def collect_services(sid, tier, people):
    """Resolves named residents, distinct roles, trainers by skill, and court roles for a settlement."""
    roles = set()
    trainers_by_skill = []
    court_roles = []
    named_residents = []

    for p in people:
        job = p.get('job', '')
        role = p.get('role') or job
        if role:
            roles.add(role)
        if job.startswith('trainer_'):
            sk = job[len('trainer_'):]
            if sk and sk not in trainers_by_skill:
                trainers_by_skill.append(sk)
        if job in COURT_ROLES and job not in court_roles:
            court_roles.append(job)

    # Merge configured trainers from trainers.json (which Housing.assignTrainers wires into the settlement)
    t_specs = TRAINERS_DATA.get('settlements', {}).get(sid, [])
    if not t_specs and tier <= 1:
        culture = SKYRIM_PLACES.get(sid, {}).get('culture', 'nord')
        defaults = TRAINERS_DATA.get('culture_defaults', {}).get(culture, [])
        want = 5 if tier == 0 else 1
        t_specs = defaults[:want]
    for spec in t_specs:
        sk = spec.get('skill')
        if sk and sk not in trainers_by_skill:
            trainers_by_skill.append(sk)
        name = spec.get('name')
        if name and name not in named_residents:
            named_residents.append(name)
        roles.add('trainer')

    # Merge court members from courts.json (which Housing.ensureCourt wires into capitals/cities)
    c_spec = COURTS_DATA.get('settlements', {}).get(sid)
    if not c_spec and tier == 0:
        culture = SKYRIM_PLACES.get(sid, {}).get('culture', 'nord')
        c_spec = COURTS_DATA.get('culture_defaults', {}).get(culture)
    if c_spec:
        for crole in COURT_ROLES:
            cname = c_spec.get(crole)
            if cname:
                if (tier == 0 or crole in court_roles or sid in COURTS_DATA.get('settlements', {})) and crole not in court_roles:
                    court_roles.append(crole)
                if cname not in named_residents:
                    named_residents.append(cname)

    return {
        'named_residents': named_residents,
        'roles': sorted(roles),
        'trainers_by_skill': trainers_by_skill,
        'court_roles': court_roles,
    }


def lint(path):
    with open(path, encoding='utf8') as f:
        d = json.load(f)
    errs, warns = [], []
    anchors = d['anchor_list']
    people = d['people_list']
    sid = d['id']
    tier = d.get('tier', 2)
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
    # the watch (owner, playtest 5): 2 posted guards at the main gate of a major settlement, 1 wandering guard in a
    # minor one, none in anything smaller; none of them is a housing resident (Helgen's scripted garrison excepted)
    watch = d.get('watch')
    if watch is not None:
        want = {'major': (2, 0), 'minor': (0, 1), 'none': (0, 0)}.get(watch)
        if want is None:
            errs.append('%s: unknown watch %r' % (sid, watch))
        elif (d.get('watch_posted'), d.get('watch_patrols')) != want:
            errs.append('%s: %s settlement has %s posted + %s patrol guards (want %d + %d)'
                        % (sid, watch, d.get('watch_posted'), d.get('watch_patrols'), want[0], want[1]))
        if sid != 'helgen' and d.get('guard_residents', 0) != 0:
            errs.append('%s: %d guard residents in the housing plan (the watch is not a household)' % (sid, d['guard_residents']))
        if not d.get('guard_profile'):
            errs.append('%s: no guard profile resolved' % sid)

    # R3d-1.4: trainers by skill and court roles (F-COURT) per settlement and tier
    services = collect_services(sid, tier, people)
    d['named_residents'] = services['named_residents']
    d['roles'] = services['roles']
    d['trainers_by_skill'] = services['trainers_by_skill']
    d['court_roles'] = services['court_roles']
    skind = resolve_settlement_kind(sid, tier)
    d['settlement_kind'] = skind

    if is_skyrim_settlement(sid):
        if skind == 'capital':
            if len(services['trainers_by_skill']) < 5:
                errs.append('%s: Skyrim capital has %d trainers (need >= 5)' % (sid, len(services['trainers_by_skill'])))
            if len(services['court_roles']) < 3:
                errs.append('%s: Skyrim capital has %d court roles (need >= 3 for F-COURT)' % (sid, len(services['court_roles'])))
        elif skind == 'city':
            if len(services['trainers_by_skill']) < 2:
                errs.append('%s: Skyrim city has %d trainers (need >= 2)' % (sid, len(services['trainers_by_skill'])))
            if len(services['court_roles']) < 3:
                errs.append('%s: Skyrim city has %d court roles (need >= 3 for F-COURT)' % (sid, len(services['court_roles'])))

    for m, n in d['idle_at'].items():
        if n > MAX_IDLE_PER_MEETING:
            warns.append('%s: %d idlers assigned to meeting point #%s (max %d)' % (sid, n, m, MAX_IDLE_PER_MEETING))
    if d['household_idle'] and not d['meetings']:
        warns.append('%s: %d household residents but no meeting point (they stay indoors)' % (sid, d['household_idle']))
    return d, errs, warns


def lint_static_rosters(seen_ids):
    """Verifies all Skyrim capitals and cities in trainers.json / courts.json even if not in the preview dump sample."""
    errs = []
    all_skyrim_seats = sorted(SKYRIM_WALLED_CAPITALS | SKYRIM_HOLD_CITIES)
    for sid in all_skyrim_seats:
        if sid in seen_ids:
            continue
        skind = resolve_settlement_kind(sid, 0)
        services = collect_services(sid, 0, [])
        if skind == 'capital':
            if len(services['trainers_by_skill']) < 5:
                errs.append('%s: Skyrim capital has %d trainers in trainers.json (need >= 5)'
                            % (sid, len(services['trainers_by_skill'])))
            if len(services['court_roles']) < 3:
                errs.append('%s: Skyrim capital has %d court roles in courts.json (need >= 3 for F-COURT)'
                            % (sid, len(services['court_roles'])))
        elif skind == 'city':
            if len(services['trainers_by_skill']) < 2:
                errs.append('%s: Skyrim city has %d trainers in trainers.json (need >= 2)'
                            % (sid, len(services['trainers_by_skill'])))
            if len(services['court_roles']) < 3:
                errs.append('%s: Skyrim city has %d court roles in courts.json (need >= 3 for F-COURT)'
                            % (sid, len(services['court_roles'])))
    return errs


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

    roster_errs = lint_static_rosters(have)
    for e in roster_errs:
        print('ERR  ' + e)
    bad += len(roster_errs)

    if table or not bad:
        print('%-16s %4s %5s %5s %5s %5s %5s %5s %-6s %-16s %-26s %-28s'
              % ('settlement', 'tier', 'beds', 'resid', 'named', 'roles', 'unstf', 'court', 'watch', 'guard', 'court_roles', 'trainers_by_skill'))
        for d in rows:
            t_skills = ','.join(d.get('trainers_by_skill', [])) or '-'
            c_roles = ','.join(d.get('court_roles', [])) or '-'
            print('%-16s %4d %5d %5d %5d %5d %5d %5d %-6s %-16s %-26s %-28s'
                  % (d['id'], d['tier'], d['capacity'], d['residents'],
                     len(d.get('named_residents', [])), len(d.get('roles', [])),
                     len(d['unstaffed']), len(d.get('court_roles', [])),
                     d.get('watch', '?'), d.get('guard_profile', '?'),
                     c_roles[:26], t_skills[:28]))
        by_tier = {}
        for d in rows:
            t = d['tier']
            bucket = by_tier.setdefault(t, {'count': 0, 'residents': 0, 'named': 0, 'roles': set(),
                                            'trainers': set(), 'court': set(), 'unstaffed': 0})
            bucket['count'] += 1
            bucket['residents'] += d['residents']
            bucket['named'] += len(d.get('named_residents', []))
            bucket['roles'].update(d.get('roles', []))
            bucket['trainers'].update(d.get('trainers_by_skill', []))
            bucket['court'].update(d.get('court_roles', []))
            bucket['unstaffed'] += len(d['unstaffed'])
        for t in sorted(by_tier):
            b = by_tier[t]
            print('tier %d summary: %d settlements, %d residents, %d named, %d roles, %d trainer skills (%s), %d court roles (%s), %d unstaffed'
                  % (t, b['count'], b['residents'], b['named'], len(b['roles']),
                     len(b['trainers']), ','.join(sorted(b['trainers'])) or 'none',
                     len(b['court']), ','.join(sorted(b['court'])) or 'none',
                     b['unstaffed']))
    print('population lint: %d settlements, %d errors' % (len(rows), bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
