import sys, os, math, json, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from plan_audit import *
R=json.load(open('audit_all.json'))
out={}
cnt=collections.Counter()
for s in range(P['ns']):
    sid=P['sId'][s]
    specs=None
    n0,n1=P['sNodeOff'][s],P['sNodeOff'][s+1]
    gate=[n for n in range(n0,n1) if P['nClass'][n]==1]
    road=[n for n in range(n0,n1) if P['nClass'][n]==5]
    gspecs=[]
    for i in range(P['sSpecOff'][s],P['sSpecOff'][s+1]):
        d=spec(i)
        if d['kind']=='GATE': gspecs.append((d['x'],d['z'],d['facing']))
    # nearest road end to each gate spec
    dists=[]
    for gx,gz,f in gspecs:
        ends=[math.hypot(P['nx'][n]-gx,P['nz'][n]-gz) for n in gate+road]
        dists.append(round(min(ends)) if ends else None)
    t=tier_of(sid)
    out[sid]=dict(tier=t,gate_nodes=len(gate),road_joins=len(road),gate_specs=len(gspecs),gate_spec_to_road_end=dists)
    cnt[(t,'no_gate_node' if not gate else 'gate_node', 'walled' if gspecs else 'open')]+=1
    cnt[(t,'road_ends_far_from_every_gate_spec' if gspecs and all(d is None or d>24 for d in dists) else 'ok')]+=1
for k,v in sorted(cnt.items()): print(k,v)
json.dump(out,open('gates_info.json','w'))
print([ (k,v) for k,v in out.items() if v['tier'] in('capital','city') and v['gate_specs'] and all(d is None or d>24 for d in v['gate_spec_to_road_end'])])
