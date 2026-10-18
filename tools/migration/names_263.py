"""Mechanical public-name/factory changes, excluding concurrently owned networking files."""
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[2]
owned=ROOT/'dist/code-review/neoforge-migration/network-transformed-files.txt'
EXCLUDED={line.strip().replace('\\','/') for line in owned.read_text().splitlines() if line.strip()}
EXCLUDED.update('mod/src/main/java/com/skycraft/'+x for x in ('core/SkyData.java','core/PlayerData.java','core/PlayerDataEvents.java','dig/PlacedBlocks.java','magic/MagicFx.java'))

def constructors(source):
    # Inspect only constructor expressions; commas inside nested calls and string/character literals are ignored.
    pattern=re.compile(r'new\s+((?:net\.minecraft\.resources\.)?Identifier)\s*\(')
    changes=[]
    for match in pattern.finditer(source):
        depth=0;commas=0;quote=None;escape=False
        for char in source[match.end():]:
            if quote:
                if escape:escape=False
                elif char=='\\':escape=True
                elif char==quote:quote=None
            elif char in ('"',"'"):quote=char
            elif char in '([{':depth+=1
            elif char==')' and depth==0:break
            elif char in ')]}':depth-=1
            elif char==',' and depth==0:commas+=1
        else:raise ValueError('Unterminated Identifier expression')
        if commas not in (0,1):raise ValueError('Unexpected Identifier arity')
        factory='parse' if commas==0 else 'fromNamespaceAndPath'
        changes.append((match.start(),match.end(),match[1]+'.'+factory+'('))
    for start,end,value in reversed(changes):source=source[:start]+value+source[end:]
    return source

changed=0
for base in ('mod/src/main/java','mod/src/test/java'):
 for path in (ROOT/base).rglob('*.java'):
    rel=path.relative_to(ROOT).as_posix()
    if rel in EXCLUDED or '/com/skycraft/network/' in rel:continue
    raw=path.read_bytes();text=raw.decode('utf8');result=text.replace('ResourceLocation','Identifier')
    result=constructors(result)
    result=result.replace('import net.neoforged.fml.DistExecutor;','import com.skycraft.platform.Sides;')
    result=result.replace('net.neoforged.fml.DistExecutor.unsafeRunWhenOn','com.skycraft.platform.Sides.runOn').replace('DistExecutor.unsafeRunWhenOn','Sides.runOn')
    result=result.replace('ModLoadingContext.get().registerConfig(', 'com.skycraft.Skycraft.config(')
    result=result.replace('net.neoforged.fml.com.skycraft.Skycraft.config(', 'com.skycraft.Skycraft.config(')
    result=re.sub(r',?\s*bus\s*=\s*net\.neoforged\.fml\.common\.EventBusSubscriber\.Bus\.MOD\s*,?',lambda m:',' if m[0].strip().endswith(',') else '',result)
    result=result.replace('(,','(').replace(',)',')')
    if result!=text:path.write_bytes(result.encode('utf8'));changed+=1
print('Changed files:',changed,'; networking exclusions:',len(EXCLUDED))
