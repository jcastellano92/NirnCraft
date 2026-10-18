"""Explicit common-game API changes; never alter another workstream's files."""
import re
from imports_263 import ROOT,owner
from nbt_263 import calls

EFFECTS={'MOVEMENT_SPEED':'SPEED','MOVEMENT_SLOWDOWN':'SLOWNESS','DIG_SPEED':'HASTE','DIG_SLOWDOWN':'MINING_FATIGUE',
         'DAMAGE_BOOST':'STRENGTH','HEAL':'INSTANT_HEALTH','HARM':'INSTANT_DAMAGE','JUMP':'JUMP_BOOST',
         'CONFUSION':'NAUSEA','DAMAGE_RESISTANCE':'RESISTANCE'}
PERMISSIONS={1:'MODERATOR',2:'GAMEMASTER',3:'ADMIN',4:'OWNER'}

def migrate():
    changed=0
    for path in (ROOT/'mod/src/main/java/com/skycraft').rglob('*.java'):
        if owner(path)!='root':continue
        source=path.read_text(encoding='utf8');result=source
        for old,new in EFFECTS.items():result=re.sub(r'\bMobEffects\.'+old+r'\b','MobEffects.'+new,result)
        for old,new in {'ADDITION':'ADD_VALUE','MULTIPLY_BASE':'ADD_MULTIPLIED_BASE','MULTIPLY_TOTAL':'ADD_MULTIPLIED_TOTAL'}.items():
            result=re.sub(r'\bOperation\.'+old+r'\b','Operation.'+new,result)
        result=result.replace('.getType().is(','.getType().builtInRegistryHolder().is(')
        for level,permission in PERMISSIONS.items():
            result=re.sub(r'\.hasPermissions?\('+str(level)+r'\)',
                          '.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_'+permission+')',result)
        for name,render in {
            'putUUID':lambda a:'store('+a[0]+', net.minecraft.core.UUIDUtil.CODEC, '+a[1]+')',
            'getUUID':lambda a:'read('+a[0]+', net.minecraft.core.UUIDUtil.CODEC).orElseThrow()',
            'hasUUID':lambda a:'read('+a[0]+', net.minecraft.core.UUIDUtil.CODEC).isPresent()',
        }.items():
            count=2 if name=='putUUID' else 1
            result=calls(result,name,lambda args,original,n=count,f=render:f(args) if len(args)==n else original)
        if source!=result:path.write_text(result,encoding='utf8');changed+=1
    print('Root common API files:',changed)

if __name__=='__main__':migrate()
