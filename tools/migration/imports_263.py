"""Resolve moved imports against actual26.3 source names. Never invent a missing class."""
from pathlib import Path
import argparse,collections,re,zipfile,json
ROOT=Path(__file__).resolve().parents[2]
SCRATCH=Path(r'E:\Skycraft-scratch\migration-26.3')
WORLD={'tamriel','worldplan','worldgen','world','roads','dungeons','scatter','landmarks','realms','society','economy','quest','explore','sailing','seas','lore','homestead'}
DATA={'core','inventory','crafting','magic','creatures','fauna','curse','mounts','arsenal','combat','vitals','crime','skills','loot','protect','dig','audio','perf','dev'}
network_list=ROOT/'dist/code-review/neoforge-migration/network-transformed-files.txt'
NETWORK={x.strip().replace('\\','/') for x in network_list.read_text().splitlines() if x.strip()}
NETWORK.update('mod/src/main/java/com/skycraft/'+x for x in ('core/SkyData.java','core/PlayerData.java','core/PlayerDataEvents.java','dig/PlacedBlocks.java','magic/MagicFx.java','creatures/CreatureItems.java','mounts/MountItems.java','registry/ModItems.java'))
def owner(path):
    rel=path.relative_to(ROOT).as_posix()
    if rel in {'mod/src/main/java/com/skycraft/perf/FastSurfaceRule.java','mod/src/main/java/com/skycraft/perf/StageBench.java','mod/src/main/java/com/skycraft/lore/StandingStoneShrineFeature.java','mod/src/main/java/com/skycraft/lore/LoreModule.java','mod/src/main/java/com/skycraft/seas/SeasRegistry.java'} or ('/com/skycraft/seas/world/' in rel and path.name.endswith('Feature.java')):return 'world'
    if rel in NETWORK or '/com/skycraft/network/' in rel:return 'network'
    module=path.relative_to(ROOT/'mod/src/main/java/com/skycraft').parts[0]
    if module=='client':return 'ui'
    if module=='visual' and '/client/' in rel and any(name in path.stem for name in ('Autotest','Review','Gallery','Tour')):return 'ui'
    if '/client/' in rel and path.name.endswith('Screen.java'):return 'ui'
    if module in DATA and '/client/' not in rel:return 'network'
    if path.name in {'SkyUi.java','SkyItemRender.java'} and '/inventory/client/' in rel:return 'ui'
    if '/client/' in rel and module not in {'client','atmosphere','visual','render'}:return 'ui'
    if module in WORLD and '/client/' not in rel:return 'world'
    return 'root'
def migrate(scope):
    names=set()
    for jar in [SCRATCH/'build/moddev/artifacts/minecraft-patched-26.3.0.90-sources.jar',SCRATCH/'neoforge-26.3.0.90-sources.jar']:
        with zipfile.ZipFile(jar) as z:names.update(n[:-5].replace('/','.') for n in z.namelist() if n.endswith('.java'))
    simple=collections.defaultdict(set)
    for n in names:simple[n.rsplit('.',1)[-1]].add(n)
    unresolved=set();changes=[]
    pattern=re.compile(r'^import ((?:net\.minecraft|net\.neoforged)\.[\w.]+);',re.M)
    for path in (ROOT/'mod/src/main/java/com/skycraft').rglob('*.java'):
        if owner(path)!=scope:continue
        raw=path.read_bytes();s=raw.decode('utf8')
        def replace(m):
            old=m[1]
            if old in names:return m[0]
            # Nested classes are not top-level source names.
            if old.rsplit('.',1)[0] in names:return m[0]
            candidates=simple.get(old.rsplit('.',1)[-1],set())
            if len(candidates)==1:
                new=next(iter(candidates));changes.append((str(path.relative_to(ROOT)),old,new));return 'import '+new+';'
            unresolved.add(old);return m[0]
        result=pattern.sub(replace,s)
        if result!=s:path.write_bytes(result.encode('utf8'))
    out=ROOT/'dist/code-review/neoforge-migration'/('moved-imports-'+scope+'.json')
    out.write_text(json.dumps({'changes':changes,'unresolved':sorted(unresolved)},indent=2)+'\n')
    print(scope,'moved imports:',len(changes),'unresolved names:',len(unresolved))
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--owner',choices=['root','world','ui','network'],required=True)
    migrate(p.parse_args().owner)
