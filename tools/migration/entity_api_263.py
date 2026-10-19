"""Explicit public entity/world API edits driven by confirmed native 26.3 declarations."""
import json,re,zipfile
from imports_263 import ROOT,owner,SCRATCH

reports=json.loads((ROOT/'dist/code-review/neoforge-migration/compile-08.json').read_text(encoding='utf8'))
access={}
for d in reports:
    p=ROOT/d['path']
    if owner(p)!='root':continue
    for field,method in [('server','level().getServer()'),('random','getRandom()')]:
        if 'private access in ServerPlayer' not in d['message'] and 'protected access in Level' not in d['message']:continue
        for m in re.finditer(r'\b(\w+(?:\.\w+(?:\(\))?)*)\.'+field+r'\b(?!\s*\()',d['context']):
            access.setdefault(p,{})[m[0]]=m[1]+'.'+method

with zipfile.ZipFile(SCRATCH/'build/moddev/artifacts/minecraft-patched-26.3.0.90-sources.jar') as z:
    entity_constants=re.findall(r'public static final EntityType<[^;=]+> (\w+) =',z.read('net/minecraft/world/entity/EntityTypes.java').decode())

for p in (ROOT/'mod/src/main/java/com/nirncraft').rglob('*.java'):
    if owner(p)!='root':continue
    s=p.read_text(encoding='utf8');v=s
    for a,b in access.get(p,{}).items():v=re.sub(re.escape(a)+r'\b',lambda _:b,v)
    for name in entity_constants:v=re.sub(r'\bEntityType\.'+name+r'\b','net.minecraft.world.entity.EntityTypes.'+name,v)
    # Navigation.moveTo retains its name. Only missing entity signatures move to snapTo.
    for diagnostic in reports:
        if diagnostic['path'] != p.relative_to(ROOT).as_posix() or 'method moveTo(' not in diagnostic['context']:continue
        for match in re.finditer(r'\b(\w+(?:\.\w+(?:\(\))?)*)\.moveTo\(',diagnostic['context']):
            v=v.replace(match[0], match[0].replace('.moveTo(', '.snapTo('))
    v=v.replace('getGameProfile().getName()', 'getGameProfile().name()').replace('getGameProfile().getId()', 'getGameProfile().id()')
    v=v.replace('ChunkPos.asLong(', 'ChunkPos.pack(')
    v=v.replace('SpawnPlacements.Type.', 'net.minecraft.world.entity.SpawnPlacementTypes.')
    v=v.replace('NbtUtils.writeBlockPos(', 'com.nirncraft.platform.NbtPositions.write(').replace('NbtUtils.readBlockPos(', 'com.nirncraft.platform.NbtPositions.read(')
    v=v.replace('BlockEvent.BreakEvent','net.neoforged.neoforge.event.level.block.BreakBlockEvent')
    v=v.replace('.getSharedSpawnPos()', '.getRespawnData().pos()').replace('.getSharedSpawnAngle()', '.getRespawnData().yaw()')
    if s!=v:p.write_text(v,encoding='utf8');print(p.relative_to(ROOT))
