"""Migrate selected ownership's real event/input names, retaining both tick phases."""
import argparse,re
from imports_263 import ROOT,owner
from java_text import matching
TICKS={name:'net.neoforged.neoforge.'+sub+name for name,sub in [
 ('ServerTickEvent','event.tick.'),('PlayerTickEvent','event.tick.'),('LevelTickEvent','event.tick.'),
 ('ClientTickEvent','client.event.'),('RenderTickEvent','client.event.') ]}
TICKS['RenderTickEvent']='net.neoforged.neoforge.client.event.RenderFrameEvent'
NAMES={
 'net.minecraft.world.entity.MobSpawnType':'net.minecraft.world.entity.EntitySpawnReason',
 'net.minecraft.world.level.pathfinder.BlockPathTypes':'net.minecraft.world.level.pathfinder.PathType',
 'net.minecraft.world.item.UseAnim':'net.minecraft.world.item.ItemUseAnimation',
 'net.neoforged.neoforge.event.entity.living.LivingHurtEvent':'net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent',
 'net.neoforged.neoforge.event.entity.living.LivingAttackEvent':'net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent',
 'net.neoforged.neoforge.event.entity.SpawnPlacementRegisterEvent':'net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent',
 'net.neoforged.neoforge.event.ForgeEventFactory':'net.neoforged.neoforge.event.EventHooks',
 'net.neoforged.neoforge.common.extensions.IForgeMenuType':'net.neoforged.neoforge.common.extensions.IMenuTypeExtension',
 'net.neoforged.neoforge.common.ToolActions':'net.neoforged.neoforge.common.ItemAbilities',
 'net.neoforged.neoforge.common.ToolAction':'net.neoforged.neoforge.common.ItemAbility',
}
def migrate(scope):
 count=0
 for path in (ROOT/'mod/src/main/java/com/skycraft').rglob('*.java'):
  if owner(path)!=scope:continue
  raw=path.read_bytes();s=raw.decode('utf8');out=s;changes=[]
  pattern=re.compile(r'TickEvent\.(\w+)\s+(\w+)')
  for m in pattern.finditer(out):
    if m[1] not in TICKS:continue
    begin=out.find('{',m.end());end=matching(out,begin);body=out[begin:end+1];var=m[2];fq=TICKS[m[1]]
    for old,new in [('START','Pre'),('END','Post')]:
      body=re.sub(r'\b'+var+r'\.phase\s*==\s*TickEvent\.Phase\.'+old,var+' instanceof '+fq+'.'+new,body)
      body=re.sub(r'\b'+var+r'\.phase\s*!=\s*TickEvent\.Phase\.'+old,'!('+var+' instanceof '+fq+'.'+new+')',body)
    if m[1]=='PlayerTickEvent':
      body=re.sub(r'\b'+var+r'\.player\b',var+'.getEntity()',body)
      body=re.sub(r'\b'+var+r'\.side\s*==\s*LogicalSide\.CLIENT',var+'.getEntity().level().isClientSide()',body)
      body=re.sub(r'\b'+var+r'\.side\s*!=\s*LogicalSide\.SERVER',var+'.getEntity().level().isClientSide()',body)
    changes.append((begin,end+1,body))
  for a,b,v in reversed(changes):out=out[:a]+v+out[b:]
  for old,new in TICKS.items():out=out.replace('TickEvent.'+old,new)
  out=out.replace('import net.neoforged.neoforge.event.TickEvent;','')
  for old,new in NAMES.items():
    oldname=old.rsplit('.',1)[-1];newname=new.rsplit('.',1)[-1]
    out=out.replace(old,new)
    if oldname!=newname:out=re.sub(r'\b'+oldname+r'\b',newname,out)
  out=re.sub(r'\.isClientSide\b(?!\s*\()', '.isClientSide()',out)
  out=out.replace('getMinBuildHeight()','getMinY()')
  out=re.sub(r'\b(\w+(?:\.\w+(?:\(\))?)*)\.getMaxBuildHeight\(\)',r'(\1.getMaxY()+1)',out)
  if out!=s:path.write_bytes(out.encode('utf8'));count+=1
 print(scope,'event/API files:',count)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--owner',choices=['root','world','ui','network'],required=True);migrate(p.parse_args().owner)
