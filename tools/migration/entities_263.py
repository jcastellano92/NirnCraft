"""Port native entity/data-builder and typed direction-property contracts in one ownership scope."""
import argparse,re
from imports_263 import ROOT,owner
def migrate(scope):
 count=0
 for path in (ROOT/'mod/src/main/java/com/skycraft').rglob('*.java'):
  if owner(path)!=scope:continue
  raw=path.read_bytes();s=raw.decode('utf8');out=s
  # Correct the earlier broad height receiver match, then reapply the bounded receiver mapping.
  out=re.sub(r'\(([\w.()]+)\.getMaxY\(\)\+1\)',r'\1.getMaxBuildHeight()',out)
  out=re.sub(r'\b(\w+(?:\.\w+(?:\(\))?)*)\.getMaxBuildHeight\(\)',r'(\1.getMaxY()+1)',out)
  if re.search(r'void defineSynchedData\(\)',out):
   out=re.sub(r'void defineSynchedData\(\)',r'void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder)',out)
   out=out.replace('super.defineSynchedData();','super.defineSynchedData(builder);')
   out=out.replace('this.entityData.define(', 'builder.define(').replace('entityData.define(', 'builder.define(')
  if 'DirectionProperty' in out:
   out=out.replace('import net.minecraft.world.level.block.state.properties.DirectionProperty;','import net.minecraft.world.level.block.state.properties.EnumProperty;')
   out=re.sub(r'\bDirectionProperty\b','EnumProperty<net.minecraft.core.Direction>',out)
  if out!=s:path.write_bytes(out.encode('utf8'));count+=1
 print(scope,'entity/API files:',count)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--owner',choices=['root','world','ui','network'],required=True);migrate(p.parse_args().owner)
