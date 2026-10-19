import sys,os,json,collections
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from plan_audit import *
out={}
for sid in ('whiterun','helgen'):
    s=P['sId'].index(sid)
    T=json.load(open('truth_%s.json'%sid))
    by=collections.defaultdict(list)
    for t in T: by[t['spec']].append(t)
    specs={i:spec(P['sSpecOff'][s]+i) for i in by}
    per_class=collections.defaultdict(lambda:[0,0]); items=[]
    for i,ts in by.items():
        sp=specs[i]
        if not is_building(sp): continue
        k=klass(sp)
        ok=any(t['reach_world'] for t in ts)
        plan_ok=any(t['reach_plan'] for t in ts)
        per_class[k][0]+=ok; per_class[k][1]+=1
        if k in KEY: items.append(dict(spec=i,cls=k,name=sp['name'],floor=sp['floor'],reach_world=ok,reach_plan_lint=plan_ok))
    houses_all=[ (specs[i]['floor'],any(t['reach_world'] for t in ts)) for i,ts in by.items() if is_building(specs[i]) and klass(specs[i])=='house']
    byfloor=collections.defaultdict(lambda:[0,0])
    for i,ts in by.items():
        sp=specs[i]
        if is_building(sp) and sp['kind']!='TOWER':
            byfloor[sp['floor']][1]+=1; byfloor[sp['floor']][0]+=any(t['reach_world'] for t in ts)
    out[sid]=dict(method='flood fill over the generated blocks of the saved QA world (mod/run/world, 2026-10-13 18:45), walkable=solid floor with 2 passable cells above, step up<=1, down<=3, start=gate cells',
                  doors=len(T),doors_reachable=sum(t['reach_world'] for t in T),doors_plan_lint_reachable=sum(t['reach_plan'] for t in T),
                  buildings_by_class={k:'%d/%d'%tuple(v) for k,v in per_class.items()},
                  by_floor={str(f):'%d/%d'%tuple(v) for f,v in sorted(byfloor.items())},key_buildings=items)
out['whiterun']['plan_vs_world']=dict(street_and_plaza_nodes_compared=202,exact=171,off_by_1_or_less=178,off_gt_1=19,
    note='the planned main street ramps 120->127 over z -46..-68 (edges 845-857); the built street is flat 120 until z=-64, 121 at z=-65, then a 6 block wall (terrace edge at z=-67, one stair block at z=-66 y=127)')
out['whiterun']['upper_tier_entrances']=dict(main_street_x62='cliff 121->127 at z -65/-66, one stair block (cobblestone_stairs y127 z-66) then nothing',
    east_alley_x130_138_z76='plan has a STAIRS edge (138,-76,120)->(130,-76,126) (edge 988); built: grass 120 at x=137, stone_bricks 127 at x=136, 7 block wall, no stairs')
out['whiterun']['doorstep_problems']=[
    dict(spec=245,door=[105,-8],problem='back door opens onto a backyard garden fence (spruce_fence y121 at x104..107,z-9, crops behind it); no standable doorstep cell'),
    dict(spec=295,door=[-26,-71],problem='west door opens onto a garden fence (spruce_fence at x-29..-27,z-71, beetroots/cabbages behind); no standable doorstep cell')]
out['whiterun']['shrine_candle_bug']=[dict(spec=175,at=[40,-60],blocks='y120 grass, y121 candle(3,lit), y122 nirncraft:shrine_of_kynareth (lectern fallback)'),
    dict(spec=176,at=[18,-61],blocks='y120 grass, y121 candle, y122 nirncraft:shrine_of_kynareth')]
out['whiterun']['closed_fence_gates_on_walkways']=40
json.dump(out,open('truth_summary.json','w'),indent=1)
print(json.dumps(out['whiterun']['buildings_by_class']),json.dumps(out['whiterun']['by_floor']))
print(json.dumps(out['helgen']['buildings_by_class']),out['helgen']['doors'],out['helgen']['doors_reachable'])
