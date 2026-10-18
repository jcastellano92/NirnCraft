"""Use native typed saved-data storage while preserving each original Skycraft payload codec."""
import re
from imports_263 import ROOT,owner
from java_text import matching,arguments

for p in (ROOT/'mod/src/main/java/com/skycraft').rglob('*.java'):
    if owner(p)!='root':continue
    s=p.read_text(encoding='utf8');v=s;edits=[]
    pattern=r'\b(\w+(?:\.\w+(?:\(\))?)*)\.getDataStorage\(\)\.computeIfAbsent\('
    for m in re.finditer(pattern,v):
        end=matching(v,m.end()-1);a=arguments(v[m.end():end])
        if len(a)!=3:continue
        replacement='com.skycraft.platform.SavedDataTypes.get('+m[1]+', '+a[2].strip()+', '+a[0].strip()+', '+a[1].strip()+', value263 -> value263.save(new net.minecraft.nbt.CompoundTag()))'
        edits.append((m.start(),end+1,replacement))
    for start,end,replacement in reversed(edits):v=v[:start]+replacement+v[end:]
    if 'extends SavedData' in v:
        v=re.sub(r'@Override\s+(public CompoundTag save\(CompoundTag \w+\))',r'\1',v)
    if s!=v:p.write_text(v,encoding='utf8');print(p.relative_to(ROOT),len(edits))
