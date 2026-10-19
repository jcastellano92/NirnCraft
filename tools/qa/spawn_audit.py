"""Audit actual runtime spawn tables across every region; no fabricated creature-sheet coverage."""
import json
from pathlib import Path
import re
import sys
from collections import Counter

ROOT=Path(__file__).resolve().parents[2]
RES=ROOT/'mod/src/main/resources'
FAMILIES={'ocean','beach','river','swamp','desert','jungle','ash','frozen','mountain','forest','plains'}


def group_errors(group):
    errors=[]
    for key,valid,default in [('kind',{'hostile','wildlife'},'hostile'),
                             ('time',{'any','day','night','dawn','dusk'},'any'),
                             ('place',{'any','surface','cave','water'},'surface'),
                             ('weather',{'any','clear','rain','storm'},'any'),
                             ('road',{'any','on','off'},'any')]:
        if group.get(key,default) not in valid: errors.append('unknown '+key)
    weight=group.get('weight',10)
    if not isinstance(weight,int) or isinstance(weight,bool) or weight<=0: errors.append('nonpositive/integer weight')
    for key,low,high in [('levels',1,999),('count',0,1000)]:
        if key not in group: continue
        value=group[key]
        pair=value if isinstance(value,list) else [value,value]
        if (len(pair)!=2 or any(not isinstance(n,int) or isinstance(n,bool) for n in pair)
                or not low<=pair[0]<=pair[1]<=high): errors.append('invalid '+key+' range')
    for low,high,default_hi in [('minY','maxY',2**31-1),('townMin','townMax',0)]:
        a,b=group.get(low,0 if low=='townMin' else -2**31),group.get(high,default_hi)
        if b!=0 and a>b: errors.append('inverted '+low+'/'+high)
    members=group.get('members',[group] if 'entity' in group else [])
    if not group.get('quiet') and not members: errors.append('group silently skipped: no members')
    if group.get('quiet') and members: errors.append('quiet slot contains actors')
    for member in members:
        if not re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9_./-]+',str(member.get('entity',''))):
            errors.append('invalid entity identifier')
        count=member.get('count',[1,1]); pair=count if isinstance(count,list) else [count,count]
        if len(pair)!=2 or any(not isinstance(n,int) for n in pair) or not 0<=pair[0]<=pair[1]<=1000:
            errors.append('invalid member count')
    return errors


def main():
    regions=json.loads((RES/'nirncraft/tamriel/regions.json').read_text())
    dimensions={'minecraft:the_nether','minecraft:the_end'}|{'nirncraft:'+p.stem for p in (RES/'data/nirncraft/dimension').glob('*.json')}
    ids={r['id'] for r in regions}|dimensions
    # Regions.dimensionRegion uses the actual dimension path as its province.
    # Beyond.build creates procedural overworld regions with province="realm".
    # These are distinct from Regions.dimensionRegion's dimension-path provinces.
    provinces={r['province'] for r in regions}|{d.split(':',1)[1] for d in dimensions}|{'realm'}
    rules=[]; errors=[]; groups=0
    for path in sorted((RES/'data/nirncraft/region_spawns').glob('*.json')):
        value=json.loads(path.read_text())
        for index,rule in enumerate(value if isinstance(value,list) else [value]):
            name=path.stem+'#'+str(index)
            for family in rule.get('families',[]):
                if family not in FAMILIES: errors.append(name+': unknown family '+family)
            for province in rule.get('provinces',[]):
                if province not in provinces: errors.append(name+': unknown province '+province)
            for rid in rule.get('regions',[]):
                if rid not in ids:
                    errors.append(name+': unknown region '+rid)
            for n,group in enumerate(rule.get('groups',[])):
                groups+=1
                errors.extend(name+'/group'+str(n)+': '+e for e in group_errors(group))
            rules.append((name,rule))
    coverage={}
    for region in regions:
        candidates=[(name,r) for name,r in rules
                    if (not r.get('regions') or region['id'] in r['regions'])
                    and (not r.get('provinces') or region['province'] in r['provinces'])]
        kinds=Counter(g.get('kind','hostile') for _,r in candidates for g in r.get('groups',[]) if not g.get('quiet'))
        coverage[region['id']]={'candidateRules':[name for name,_ in candidates],'groups':dict(kinds),
            'limit':'Candidate filters only. Actual biome/time/height/weather eligibility and movement require runtime checks.'}
        if not candidates: errors.append(region['id']+': no candidate runtime spawn rules')
    out=ROOT/'dist/world-tests/spawn-audit.json'
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps({'rules':len(rules),'groups':groups,'regions':coverage,'errors':errors},indent=2)+'\n',encoding='utf-8')
    for error in errors[:40]: print('ERR',error)
    print(f'{len(rules)} actual spawn rules, {groups} groups, {len(coverage)} regions, {len(errors)} errors; {out}')
    return int(bool(errors))


if __name__=='__main__': sys.exit(main())
