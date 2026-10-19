"""Apply confirmed native API renames only in root-owned files that failed for that method."""
import collections,json,re
from pathlib import Path
from imports_263 import ROOT,owner

METHODS={
    'serverLevel':'level',
    # 1.20.1 getDayTime() was the shared overworld day time in every dimension; getDefaultClockTime() is the
    # dimension's own clock (paused in the fixed-time realms)
    'getDayTime':'getOverworldClockTime',
    'location':'identifier',
    'setPixelRGBA':'setPixelABGR',
    'getParamOrNull':'getOptional',
    'isSameItemSameTags':'isSameItemSameComponents',
    'setSecondsOnFire':'igniteForSeconds',
}

def migrate(log):
    candidates=collections.defaultdict(set)
    for diagnostic in json.loads(Path(log).read_text(encoding='utf8')):
        path=ROOT/diagnostic['path']
        if owner(path)!='root':continue
        match=re.search(r'symbol:\s+method (\w+)\(',diagnostic['context'])
        if match and match[1] in METHODS:candidates[path].add(match[1])
    changed=0
    for path,names in candidates.items():
        source=path.read_text(encoding='utf8');result=source
        for name in names:
            result=re.sub(r'\.'+name+r'\s*\(','.'+METHODS[name]+'(',result)
        if result!=source:
            path.write_text(result,encoding='utf8');changed+=1
    print('Confirmed API renames:',changed,'files')
if __name__=='__main__':
    import sys
    migrate(sys.argv[1])
