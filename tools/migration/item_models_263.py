"""Publish native26.3 item definitions from preserved Skycraft geometry and legacy predicates."""
from pathlib import Path
import json,re
from java_text import matching
ROOT=Path(__file__).resolve().parents[2]
MODELS=ROOT/'mod/src/main/resources/assets/skycraft/models/item'
OUT=ROOT/'mod/src/main/resources/assets/skycraft/items'
def model(ref):
    result={'type':'minecraft:model','model':ref}
    tint={'skycraft:item/graht_oak_leaves':0x4e9a32,'skycraft:item/hist_leaves':0x5a7a3a}.get(ref)
    if tint is not None: result['tints']=[{'type':'minecraft:constant','value':tint}]
    if ref=='skycraft:item/regional_treasure':result['tints']=[{'type':'skycraft:treasure'},{'type':'minecraft:constant','value':0xffffff}]
    return result
def test(key,value,yes,no,crossbow=False):
    if key in ('pulling','blocking','cast'):
        return {'type':'minecraft:condition','property':'minecraft:fishing_rod/cast' if key=='cast' else 'minecraft:using_item','on_true':yes,'on_false':no}
    if key in ('charged','firework'):
        return {'type':'minecraft:select','property':'minecraft:charge_type','cases':[{'when':'rocket' if key=='firework' else ['arrow','rocket'],'model':yes}],'fallback':no}
    props={'custom_model_data':'minecraft:custom_model_data','skycraft:filled':'skycraft:filled','skycraft:cover':'skycraft:cover','pull':'minecraft:crossbow/pull' if crossbow else 'minecraft:use_duration'}
    if key not in props: raise ValueError(f'Unmigrated item predicate {key}')
    out={'type':'minecraft:range_dispatch','property':props[key],'entries':[{'threshold':value,'model':yes}],'fallback':no}
    if key=='pull' and not crossbow: out['scale']=.05
    return out
def convert(ref,source):
    current=model(ref)
    for override in source.get('overrides',[]):
        selected=model(override['model'])
        for key,value in reversed(list(override['predicate'].items())):
            selected=test(key,value,selected,current,'crossbow' in ref)
        current=selected
    return {'model':current}
def egg_palettes():
    """Read active registration palettes; unused alternative specs cannot override live eggs."""
    base=ROOT/'mod/src/main/java/com/skycraft'
    palettes={}
    for relative in ('creatures/ModEntities.java','fauna/FaunaEntities.java','curse/CurseRegistry.java'):
        source=(base/relative).read_text(encoding='utf8')
        for name,bg,fg in re.findall(r'egg\("([a-z0-9_]+)"\s*,[^,]+,\s*(0x[0-9A-Fa-f]+)\s*,\s*(0x[0-9A-Fa-f]+)\)',source):
            key=name+'_spawn_egg'
            if key in palettes:raise ValueError('Duplicate active spawn egg '+key)
            palettes[key]=(int(bg,16),int(fg,16))
    factories=(('creatures/BestiarySpecs.java','creatures/Bestiary.java','BestiarySpecs.'),
        ('seas/SeaSpecs.java','seas/SeasRegistry.java','SeaSpecs.'),
        ('realms/creatures/RealmCreatures.java','realms/creatures/RealmCreatures.java',''))
    for spec_file,registration_file,prefix in factories:
        registration=(base/registration_file).read_text(encoding='utf8')
        active=set(re.findall(re.escape(prefix)+r'([A-Za-z0-9_]+)\(\)',registration)) if prefix else set(re.findall(r'Bestiary\.(?:register|monster|beast)\(([A-Za-z0-9_]+)\(\)',registration))
        source=(base/spec_file).read_text(encoding='utf8')
        for method in re.finditer(r'static CreatureSpec ([A-Za-z0-9_]+)\(\)\s*\{',source):
            if method[1] not in active:continue
            body=source[method.end():matching(source,method.end()-1)]
            name=re.search(r'new CreatureSpec\("([a-z0-9_]+)"\)',body)
            palette=re.search(r'\.egg\((0x[0-9A-Fa-f]+),\s*(0x[0-9A-Fa-f]+)\)',body)
            if not name or not palette:raise ValueError('Missing egg palette for '+method[1])
            key=name[1]+'_spawn_egg'
            if key in palettes:raise ValueError('Duplicate active spawn egg '+key)
            palettes[key]=(int(palette[1],16),int(palette[2],16))
    # Original ForgeSpawnEggItem constructor palettes, moved here because native SpawnEggItem no longer stores them.
    palettes.update({'river_betty_spawn_egg':(0x7A8A6A,0xC8B070),'abecean_longfin_spawn_egg':(0x4A7AA0,0xE0C060),
        'npc_spawn_egg':(0x8A6A44,0xD8C8A0),'npc_fighter_spawn_egg':(0x3A3A44,0xB03030),
        'dwarven_spider_spawn_egg':(0xB0803A,0x4FD8FF),'dwarven_sphere_spawn_egg':(0xA87432,0x3A3A3A),'dwarven_centurion_spawn_egg':(0x8C5E2A,0xFFB040)})
    return palettes

def egg_template():
    """Original voxel egg geometry; two authored palette slots and a native neutral material."""
    elements=[]
    def box(start,end,tint):
        elements.append({'from':start,'to':end,'faces':{face:{'uv':[0,0,16,16],'texture':'#shell','tintindex':tint} for face in ('north','south','east','west','up','down')}})
    # The upper end narrows faster than the base, retaining a clear egg silhouette at icon scale.
    for y0,y1,radius in ((2,3,2),(3,5,3),(5,8,4),(8,11,3.6),(11,13,2.7),(13,14,1.6)):
        box([8-radius,y0,8-radius],[8+radius,y1,8+radius],0)
    for x0,y0,x1,y1,z in ((5,5,7,7,3.98),(9,7,11,9,3.98),(7,10,9,11.8,4.38),(7,3.5,9,4.5,4.98)):
        box([x0,y0,z],[x1,y1,z+.04],1)
        box([16-x1,y0,16-z-.04],[16-x0,y1,16-z],1)
    return {'parent':'minecraft:item/handheld','textures':{'shell':'minecraft:block/white_concrete','particle':'minecraft:block/white_concrete'},'gui_light':'front','elements':elements,
        'display':{'gui':{'rotation':[12,35,0],'translation':[0,0,0],'scale':[1,1,1]},'ground':{'rotation':[0,0,0],'translation':[0,2,0],'scale':[.5,.5,.5]},'fixed':{'rotation':[0,0,0],'translation':[0,0,0],'scale':[.75,.75,.75]}}}

def main():
    count=0;variants=0;palettes=egg_palettes()
    template=MODELS/'template_skycraft_spawn_egg.json'
    template.write_text(json.dumps(egg_template(),indent=2)+'\n',encoding='utf8')
    for path in sorted(MODELS.rglob('*.json')):
        if path==template:continue
        source=json.loads(path.read_text(encoding='utf8'));relative=path.relative_to(MODELS)
        ref='skycraft:item/'+relative.with_suffix('').as_posix()
        if path.stem.endswith('_spawn_egg'):
            if path.stem not in palettes:raise ValueError('No active palette for '+path.stem)
            path.write_text(json.dumps({'parent':'skycraft:item/template_skycraft_spawn_egg'},indent=2)+'\n',encoding='utf8')
            result={'model':{'type':'minecraft:model','model':ref,'tints':[{'type':'minecraft:constant','value':color} for color in palettes[path.stem]]}}
        else:result=convert(ref,source)
        target=OUT/relative;target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
        count+=1;variants+=bool(source.get('overrides'))
    missing=set(palettes)-{p.stem for p in MODELS.glob('*_spawn_egg.json')}
    if missing:raise ValueError('Missing authored egg geometry definitions: '+str(sorted(missing)))
    print(f'Native item definitions: {count}; authored variant sets: {variants}; spawn egg palettes: {len(palettes)}')
if __name__=='__main__': main()
