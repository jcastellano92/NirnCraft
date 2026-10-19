import sys, os, json, collections, math, statistics, hashlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from plan_audit import *

R = json.load(open('audit_all.json'))
B = json.load(open('budget.json'))
G = json.load(open('gates_info.json'))
TRUTH = {}
for sid in ('whiterun', 'helgen'):
    try:
        TRUTH[sid] = json.load(open('truth_%s.json' % sid))
    except Exception:
        pass

# ---- static facts read from the code (see docs/world/TOWN-AUDIT.md section 6 for file:line)
ROLE_FACTS = {
    'JARL': dict(npc_role='JARL', workplace_kind='PALACE', work_hours='06-20', spawn_cap='capital/city only (tier 0)', routine='stands near throne block, no sit', anchor_from=['PALACE', 'KEEP', 'LONGHOUSE+SEAT']),
    'HOUSECARL': dict(npc_role='HOUSECARL', workplace_kind=None, work_hours=None, spawn_cap='capital/city only', routine='none (default WANDER 06-19, INN 19-22)', anchor_from=['PALACE', 'KEEP', 'LONGHOUSE+SEAT']),
    'STEWARD': dict(npc_role='COURIER+nirncraft_steward', workplace_kind=None, work_hours=None, spawn_cap='1 where a PALACE/KEEP is built', routine='none by day (not townsfolk; sleeps at night)', anchor_from=['PALACE', 'KEEP']),
    'WIZARD': dict(npc_role='MAGE', workplace_kind=None, work_hours=None, spawn_cap='1 where a PALACE/KEEP/MAGE_TOWER is built', routine='none (default WANDER)', anchor_from=['PALACE', 'KEEP', 'MAGE_TOWER']),
    'INN': dict(npc_role='INNKEEPER', workplace_kind='INN', work_hours='06-24', spawn_cap=3, routine='works', anchor_from=['INN']),
    'BARD': dict(npc_role='BARD', workplace_kind='INN', work_hours='12-14,17-02', spawn_cap='3 (tier 0-1), 1 (village)', routine='works', anchor_from=['INN']),
    'PRIEST': dict(npc_role='PRIEST', workplace_kind='TEMPLE', work_hours='06-20', spawn_cap=3, routine='works', anchor_from=['TEMPLE']),
    'SMITH': dict(npc_role='BLACKSMITH', workplace_kind='FORGE', work_hours='08-20', spawn_cap=3, routine='works (forge is outside the shop, in the annex)', anchor_from=['SMITHY']),
    'MERCHANT': dict(npc_role='MERCHANT', workplace_kind='COUNTER', work_hours='08-20', spawn_cap='8 capital/city, 3 town, 2 village; stalls take cap first', routine='works if staffed', anchor_from=['STORE', 'STALL', 'TAILOR', 'FLETCHER']),
    'ALCHEMIST': dict(npc_role='APOTHECARY', workplace_kind='ALCHEMY', work_hours='08-20', spawn_cap=3, routine='works', anchor_from=['ALCHEMIST']),
    'GUARD': dict(npc_role='GuardEntity', workplace_kind=None, work_hours='n/a', spawn_cap='GuardWatch: 2 posted at major gates, 1 patrol in minor', routine='GuardWatch (no Schedule)', anchor_from=['BARRACKS', 'GUARDHOUSE']),
    'GUILD_LEADER': dict(npc_role='guild', workplace_kind=None, work_hours=None, spawn_cap=12, routine='unknown', anchor_from=['GUILD_HALL (legacy Archetype only)']),
}
REQUIRED = {'capital': ['JARL', 'HOUSECARL', 'STEWARD', 'WIZARD', 'INN', 'BARD', 'PRIEST', 'SMITH', 'MERCHANT', 'ALCHEMIST', 'GUARD'],
            'city': ['JARL', 'HOUSECARL', 'STEWARD', 'WIZARD', 'INN', 'PRIEST', 'SMITH', 'MERCHANT', 'ALCHEMIST', 'GUARD'],
            'town': ['MERCHANT'], 'village': [], 'hamlet': []}


def providers(kb_list, specs_by_idx):
    """role -> list of provider building spec indices (with a door), from the key buildings' kinds"""
    out = collections.defaultdict(list)
    for kb in kb_list:
        c, kind, use = kb['cls'], kb['kind'], kb['use']
        seat = False
        if c == 'leader_hall':
            sp = specs_by_idx[kb['spec']]
            seat = bool(sp['flags'] & F_SEAT)
            if kind == 'LANDMARK':
                v = specs_by_idx[kb['spec']]['variant']
                roles = {7: ('JARL', 'STEWARD'), 8: ('JARL', 'HOUSECARL'), 5: ('JARL', 'WIZARD')}.get(v, ('JARL', 'HOUSECARL', 'STEWARD', 'WIZARD'))
                for r in roles:
                    out[r].append(kb)
            elif kind == 'PALACE' or use == 'KEEP':
                for r in ('JARL', 'HOUSECARL', 'STEWARD', 'WIZARD'):
                    out[r].append(kb)
            elif kind == 'LONGHOUSE' or use == 'LONGHOUSE':
                for r in ('JARL', 'HOUSECARL'):
                    out[r].append(kb)
            else:
                out['JARL'].append(kb)
        elif c == 'inn':
            out['INN'].append(kb); out['BARD'].append(kb)
        elif c == 'temple':
            out['PRIEST'].append(kb)
        elif c == 'shop_smith':
            out['SMITH'].append(kb)
        elif c in ('shop_general', 'shop_tailor', 'shop_fletcher'):
            out['MERCHANT'].append(kb)
        elif c == 'shop_apothecary':
            out['ALCHEMIST'].append(kb)
        elif c == 'mage_tower':
            out['WIZARD'].append(kb)
        elif c == 'garrison':
            out['GUARD'].append(kb)
        elif c == 'guild_hall':
            out['GUILD_LEADER'].append(kb)
    return out


import glob
UNIQUE = {os.path.basename(f)[:-5] for f in glob.glob(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'mod', 'src', 'main', 'resources', 'nirncraft', 'tamriel', 'cityplans', '*.json'))} - {'index'}
UNIQUE |= {'whiterun','solitude','windhelm','riften','markarth','winterhold','falkreath','morthal','dawnstar','college_of_winterhold','helgen',
           'dragon_bridge','imperial_city','mournhold','vivec','alinor','sentinel','torval','wayrest','orsinium','elden_root','lilmoth','raven_rock'}

s_index = {sid: i for i, sid in enumerate(P['sId'])}
rows = []
for r in R:
    sid = r['id']
    s = s_index[sid]
    specs = [spec(i) for i in range(P['sSpecOff'][s], P['sSpecOff'][s + 1])]
    # stalls (market vendors) count as merchants
    stalls = sum(1 for sp in specs if sp['kind'] == 'STALL' and not sp['flags'] & F_OMIT)
    vend = sum(1 for sp in specs if sp['kind'] == 'YARD' and sp['variant'] % 32 == 13 and not sp['flags'] & F_OMIT)
    # guild halls by builder
    gh_legacy = sum(1 for sp in specs if sp['kind'] == 'GUILD_HALL' and not sp['flags'] & F_OMIT)
    gh_grammar = sum(1 for sp in specs if sp['kind'] == 'BUILDING' and sp['use'] == 'GUILD_HALL' and not sp['flags'] & F_OMIT)
    # stairs between levels
    stairs = collections.Counter()
    for e in range(P['sEdgeOff'][s], P['sEdgeOff'][s + 1]):
        if P['eClass'][e] == 4:
            ha, hb = P['nh'][P['ea'][e]], P['nh'][P['eb'][e]]
            if abs(ha - hb) >= 2:
                stairs['%d->%d' % (min(ha, hb), max(ha, hb))] += 1
    gnd = collections.Counter()
    for sp in specs:
        if sp['flags'] & F_GROUND and sp['floor'] != -2147483648 and sp['kind'] in ('TERRACE', 'LANDMARK'):
            gnd[sp['floor']] += 1
    prov = providers(r['key_buildings'], {i: sp for i, sp in enumerate(specs)})
    jobs = []
    tier = r['tier']
    for role in REQUIRED.get(tier, []):
        pl = prov.get(role, [])
        facts = ROLE_FACTS.get(role, {})
        with_door = [k for k in pl if k['doors'] > 0]
        reach = [k for k in pl if k['status'] == 'reachable']
        if role == 'MERCHANT':
            n_anchor = len(pl) + stalls
        else:
            n_anchor = len(pl)
        if role == 'GUARD':
            status = 'posted_by_guardwatch'
        elif n_anchor == 0:
            status = 'missing_anchor'
        elif role in ('HOUSECARL', 'STEWARD', 'WIZARD'):
            status = 'anchor_present_no_work_routine'
        elif role == 'JARL':
            status = 'anchor_present_standing_only'
        elif pl and not reach and role != 'MERCHANT':
            status = 'anchor_present_unreachable_from_gate'
        else:
            status = 'ok'
        jobs.append(dict(role=role, anchors_in_plan=n_anchor, providers_without_declared_door=len(pl) - len(with_door), reachable_providers=len(reach), status=status,
                         routine=facts.get('routine'), work_hours=facts.get('work_hours')))
    row = dict(id=sid, tier=tier, type=r['type'], culture=r['culture'], region=r['region'], x=r['x'], z=r['z'], reach=r['reach'],
               unique_layout=sid in UNIQUE, buildings=r['buildings'],
               gates=r['gates'][:8], gate_spec_to_road_end=G.get(sid, {}).get('gate_spec_to_road_end'),
               gate_nodes=G.get(sid, {}).get('gate_nodes'), road_joins=G.get(sid, {}).get('road_joins'),
               graph=r['graph'], doors=r['doors'],
               key_buildings=r['key_buildings'], key_summary=r['key_summary'],
               tier_connection=dict(levels=r['tiers']['levels'], connected=r['tiers']['connected'],
                                    ground_levels={str(k): v for k, v in sorted(gnd.items())},
                                    stairs_links=dict(stairs), height_step_gaps=r['step_gap_count'], step_gap_items=r['step_gaps'][:10],
                                    steep_stairs=r['walk']['steep_stairs_n']),
               props_on_street=r['props'], shrines=r['shrines'], scatter_in_core=r['scatter_core'],
               house_repeats=r['repeats'], areas_by_class=r['areas'],
               counts_vs_tier_floor=r['counts_vs_floor'], merchants_incl_stalls=r['counts_vs_floor']['counts']['merchants'] + stalls + vend,
               stalls=stalls, vendor_yards=vend, guild_halls=dict(legacy_with_anchors=gh_legacy, grammar_without_anchors=gh_grammar),
               npc_jobs=jobs, free_space=B.get(sid))
    rows.append(row)
json.dump(rows, open('rows.json', 'w'))
print(len(rows))
