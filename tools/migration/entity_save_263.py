"""Keep custom entity NBT fields while adopting native ValueInput/ValueOutput overrides."""
import argparse,re
from imports_263 import ROOT,owner
from java_text import matching,arguments

def migrate(scope):
    changed=0
    for path in (ROOT/'mod/src/main/java/com/skycraft').rglob('*.java'):
        if owner(path)!=scope:continue
        source=path.read_text(encoding='utf8');edits=[]
        pattern=r'void (addAdditionalSaveData|readAdditionalSaveData)\((?:net\.minecraft\.nbt\.)?CompoundTag (\w+)\)\s*\{'
        for match in re.finditer(pattern,source):
            name,param=match[1],match[2];start=match.end()-1;end=matching(source,start)
            body=source[start+1:end]
            is_read=name.startswith('read')
            value='input263' if is_read else 'output263'
            super_pattern=r'super\.'+name+r'\('+param+r'\);'
            super_call=re.search(super_pattern,body)
            prefix=''
            if super_call:
                prefix='\n        super.'+name+'('+value+');'
                body=body[:super_call.start()]+body[super_call.end():]
            if is_read:
                keys=[]
                for call in re.finditer(r'\b'+param+r'\.\w+\(',body):
                    open_paren=call.end()-1
                    parts=arguments(body[open_paren+1:matching(body,open_paren)])
                    if not parts or not parts[0].strip():continue
                    key=parts[0].strip()
                    if not re.fullmatch(r'"(?:[^"\\]|\\.)*"',key):
                        raise ValueError(str(path)+': nonliteral saved field needs review: '+key)
                    if key not in keys:keys.append(key)
                init='com.skycraft.platform.ValueTags.read('+value+(' , '+', '.join(keys) if keys else '')+')'
                suffix=''
            else:
                init='new net.minecraft.nbt.CompoundTag()'
                if re.search(r'\breturn\b',re.sub(r'//[^\n]*','',body)):
                    raise ValueError(str(path)+': save returns need explicit review')
                suffix='\n        '+value+'.store('+param+');\n    '
            signature='void '+name+'(net.minecraft.world.level.storage.Value'+('Input' if is_read else 'Output')+' '+value+') {'
            newbody=prefix+'\n        net.minecraft.nbt.CompoundTag '+param+' = '+init+';'+body+suffix
            edits.append((match.start(),end+1,signature+newbody+'}'))
        result=source
        for start,end,value in reversed(edits):result=result[:start]+value+result[end:]
        if source!=result:path.write_text(result,encoding='utf8');changed+=1;print(path.relative_to(ROOT),len(edits),'overrides')
    print(scope,changed,'entity save files')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--owner',required=True,choices=['root','world','network','ui'])
    migrate(p.parse_args().owner)
