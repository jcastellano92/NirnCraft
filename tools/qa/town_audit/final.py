import sys, os, json, collections, statistics, datetime
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import static_facts as SF
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..'))
rows = json.load(open('rows.json'))
truth = json.load(open('truth_summary.json')) if os.path.exists('truth_summary.json') else {}
manifest = json.load(open(REPO + '/mod/src/main/resources/nirncraft/baked/manifest.json'))
TIERS = ('capital', 'city', 'town', 'village', 'hamlet')
bt = collections.defaultdict(list)
for r in rows:
    bt[r['tier']].append(r)


def keystat(rs):
    c = collections.Counter()
    for r in rs:
        for b in r['key_buildings']:
            c[b['status']] += 1
    return dict(c)


agg = {}
for t in TIERS:
    rs = bt[t]
    jobs = collections.Counter()
    for r in rs:
        for j in r['npc_jobs']:
            jobs['%s:%s' % (j['role'], j['status'])] += 1
    sm = []
    for r in rs:
        for b in r['key_buildings']:
            if b['cls'].startswith('shop'):
                sm.append(b['w'] * b['d'])
    fs = [r['free_space'] for r in rs if r['free_space']]
    agg[t] = dict(
        settlements=len(rs),
        key_buildings_status=keystat(rs),
        settlements_with_unreachable_key=sum(1 for r in rs if any(v['not_reachable'] for k, v in r['key_summary'].items() if k != 'jail')),
        settlements_tier_disconnected=sum(1 for r in rs if r['tier_connection']['connected'] is False),
        props_on_street=sum(r['props_on_street']['on_street'] for r in rs),
        settlements_with_props_on_street=sum(1 for r in rs if r['props_on_street']['on_street']),
        shrines_w1=sum(r['shrines']['w1'] for r in rs), shrines_on_street=sum(r['shrines']['on_street'] for r in rs),
        scatter_in_core=sum(r['scatter_in_core']['count'] for r in rs),
        settlements_with_scatter_in_core=sum(1 for r in rs if r['scatter_in_core']['count']),
        houses=sum(r['house_repeats']['houses'] for r in rs),
        settlements_lt4_house_footprints=sum(1 for r in rs if r['house_repeats']['houses'] >= 4 and r['house_repeats']['distinct_footprints'] < 4),
        median_small_house_pct=statistics.median([r['house_repeats']['small_pct'] for r in rs if r['house_repeats']['houses']] or [0]),
        median_max_same_footprint=statistics.median([r['house_repeats']['max_same_footprint'] for r in rs if r['house_repeats']['houses']] or [0]),
        shops_total=len(sm), shops_le_63_cells=sum(1 for a in sm if a <= 63),
        no_leader_hall=sum(1 for r in rs if not r['key_summary'].get('leader_hall', {}).get('n')),
        no_gate_node=sum(1 for r in rs if r['gate_nodes'] == 0),
        npc_job_status=dict(jobs),
        counts_meet_4_4_floor={k: sum(1 for r in rs if r['counts_vs_tier_floor']['meets'][k]) for k in ('interiors', 'named', 'merchants', 'inns')},
        free_space_median={k: statistics.median([f[k] for f in fs] or [0]) for k in ('area_circle', 'free', 'soft_works', 'occupied', 'street_cells')},
        lot_fit={name: dict(free=sum(1 for f in fs if f['fit'].get(name, {}).get('free')),
                            reclaim=sum(1 for f in fs if f['fit'].get(name, {}).get('reclaim')),
                            size=next((f['fit'][name]['size'] for f in fs if name in f['fit']), None))
                 for name in ('leader_hall', 'inn', 'shop', 'smithy', 'house_rich')})
sky = [r['id'] for r in rows if (r['region'] or '').startswith('skyrim/') and r['tier'] in ('capital', 'city', 'town')]
doc = dict(
    schema='nirncraft.town_audit/1', step='TO0', generated='2026-10-13',
    inputs=dict(streetplan_bake_inputs=manifest['inputs'],
                streetplan_bin='mod/src/main/resources/nirncraft/baked/streetplan.bin (version 1, 884 settlements, 60153 nodes, 57709 edges, 12936 doors, 63807 plots, 64549 specs)',
                world_sample='mod/run/world (saved QA world with whiterun, helgen and 5 farms generated), used for world_truth only',
                places='mod/src/main/resources/nirncraft/tamriel/places/*.json; tier from the place type (place type capital in skyrim_settlements counts as capital only for the five hold capitals)'),
    definitions={
        'tier': 'capital|city|town|village|hamlet = DESIGN-PRINCIPLES 4.4',
        'key_buildings.status': 'reachable = a door cell of the building is connected to a gate over the plan walk raster (street/lane/alley bands, bridges, stairs edges, plaza rectangles, gate passages, doorstep paths; 8-neighbour, height step <= 1, <= 2 on stairs cells); street_only = the planner found a street for the door (StreetPlan dReach true) but that street is not connected to any gate; unreachable = no walk; no_door = the builder declares no entrance (Canvas.door instead of Canvas.entrance: jails, some landmarks), so the plan cannot test it',
        'gates': 'nodes of class GATE (a planned road end within 16 blocks of the predicted gate); road_join nodes seed the walk when a settlement has no GATE node',
        'tier_connection.levels': 'building floors (y of the floor block) with >= 2 enterable buildings: how many have a door connected to a gate',
        'props_on_street': 'non-OMIT YARD/LAMP/WELL/STALL specs whose footprint overlaps a street/lane/alley/path band outside plazas',
        'scatter_in_core': 'non-OMIT FIELD/GARDEN/PEN/HOMESTEAD_PLOT specs within 0.4-0.6 x reach of the centre with >= 4 buildings within 20 blocks',
        'house_repeats': 'houses = HOVEL/COTTAGE/HOUSE/TOWNHOUSE/FARMHOUSE specs; footprints by WxD; small = footprint <= 63 cells (7x9)',
        'areas_by_class': 'footprint and first-floor interior cells ((w-2)*(d-2), before annex/porch/stair strip); n = buildings of the class',
        'free_space': 'cells inside 0.85 x reach: occupied (buildings + 1 margin, walls, streets, plazas), soft_works (gardens, pens, fields, props, camps: reclaimable), free; fit.<class>.free/reclaim = a lot of that size (+1 margin) fits with a street within 3 blocks; terrain slope and water are not in the plan, so fits are optimistic',
        'npc_jobs.status': 'ok | missing_anchor (no building of the settlement provides the role) | anchor_present_no_work_routine (HOUSECARL/STEWARD/WIZARD: Schedule has no WORK branch for them) | anchor_present_standing_only (JARL: stands near the throne block, no seat) | anchor_present_unreachable_from_gate | posted_by_guardwatch'},
    tier_floors_4_4=dict(
        capital=dict(interiors=27, named=23, merchants=15, inns=1), city=dict(interiors=10, named=11, merchants=4, inns=1),
        town=dict(interiors=4.5, named=3.5, merchants=1, inns=0.5), village=dict(interiors=2, named=1, merchants=0, inns=0),
        hamlet=dict(interiors=1, named=0, merchants=0, inns=0),
        note='DESIGN-PRINCIPLES 4.4 (WORLD-PLAN only cites it). The repo holds no Skyrim footprint or interior-area medians; size_targets are proposals.'),
    size_targets=dict(
        note='proposed minimum lot (w x d, with porch and annex) per tier, to be confirmed by the owner; an interior must hold a counter, staff and 6 customers (shops) or common room + bar + 4 rooms (inns)',
        capital=dict(leader_hall='31x41', inn='21x25', shop='15x17', smithy='17x15', house_rich='15x17'),
        city=dict(leader_hall='25x31', inn='19x23', shop='13x15', smithy='15x13', house_rich='13x15'),
        town=dict(leader_hall='19x25', inn='17x21', shop='13x15', smithy='15x13', house_rich='13x13'),
        village=dict(leader_hall='15x19', inn='15x17', shop='11x13', smithy='13x11', house_rich='11x11'),
        hamlet=dict(leader_hall='11x13', inn='13x15', shop='9x11', smithy='11x9', house_rich='9x11')),
    use_sizes=json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'use_sizes.json'))),
    equipment_matrix=SF.EQUIPMENT_MATRIX, findings=SF.FINDINGS, routine_causes=SF.ROUTINE_CAUSES,
    aggregate=agg, skyrim_priority=sky, world_truth=truth, settlements=rows)
out = REPO + '/docs/world/town_audit.json'
open(out, 'w', encoding='utf8').write(json.dumps(doc, separators=(',', ':')))
print(os.path.getsize(out) / 1e6, 'MB')
json.dump(agg, open('agg.json', 'w'), indent=1)
print(json.dumps(agg, indent=1)[:9000])
