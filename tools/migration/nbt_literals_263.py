"""Preserve legacy default-value reads for literal NBT keys in an owned source scope."""
import argparse,re
from imports_263 import ROOT,owner
from java_text import matching,arguments
DEFAULTS={'getByte':'(byte)0','getShort':'(short)0','getInt':'0','getLong':'0L','getFloat':'0F','getDouble':'0D','getString':'""','getBoolean':'false'}
def migrate(scope):
 count=0
 for path in (ROOT/'mod/src/main/java/com/skycraft').rglob('*.java'):
  if owner(path)!=scope:continue
  raw=path.read_bytes();s=raw.decode('utf8');changes=[]
  pattern=re.compile(r'\.(getByte|getShort|getInt|getLong|getFloat|getDouble|getString|getBoolean|getCompound|getList)\(\s*"')
  for m in pattern.finditer(s):
   start=s.index('(',m.start());end=matching(s,start);args=arguments(s[start+1:end]);name=m[1]
   if name=='getList' and len(args)==2:
    value='.getListOrEmpty('+args[0]+')'
   elif name=='getCompound' and len(args)==1:value='.getCompoundOrEmpty('+args[0]+')'
   elif name in DEFAULTS and len(args)==1:value='.'+name+'Or('+args[0]+','+DEFAULTS[name]+')'
   else:continue
   # New APIs written by the port already unwrap Optional explicitly; leave them alone.
   after=s[end+1:end+50]
   if re.match(r'\.(?:orElse|map|flatMap|ifPresent|isPresent|isEmpty|filter)\b',after):continue
   changes.append((m.start(),end+1,value))
  # Nested expressions cannot safely be transformed together. Literal-key reads above cannot nest in a key.
  out=s
  for a,b,value in reversed(changes):out=out[:a]+value+out[b:]
  out=out.replace('.getAllKeys()', '.keySet()')
  if out!=s:path.write_bytes(out.encode('utf8'));count+=1
 print(scope,'literal NBT files:',count)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--owner',choices=['root','world','ui','network'],required=True);migrate(p.parse_args().owner)
