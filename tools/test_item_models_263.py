"""Regression checks for authored item predicates and native26.3 definitions."""
import itertools,json,sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent/'migration'))
from item_models_263 import convert,egg_palettes,MODELS,OUT

def resolve(tree,state):
    kind=tree['type'].split(':')[-1]
    if kind=='model':return tree['model']
    prop=tree['property']
    if kind=='condition':
        yes=state.get('cast',0)>=1 if prop=='minecraft:fishing_rod/cast' else state.get('pulling',state.get('blocking',0))>=1
        return resolve(tree['on_true'] if yes else tree['on_false'],state)
    if kind=='select':
        value='rocket' if state.get('firework',0)>=1 else 'arrow' if state.get('charged',0)>=1 else 'none'
        for case in tree['cases']:
            if value in (case['when'] if isinstance(case['when'],list) else [case['when']]):return resolve(case['model'],state)
        return resolve(tree['fallback'],state)
    key={'minecraft:custom_model_data':'custom_model_data','nirncraft:filled':'nirncraft:filled','nirncraft:cover':'nirncraft:cover','minecraft:crossbow/pull':'pull','minecraft:use_duration':'pull'}[prop]
    value=state.get(key,0)
    selected=tree['fallback']
    for entry in tree['entries']:
        if value>=entry['threshold']:selected=entry['model']
    return resolve(selected,state)

class NativeItemModelsTest(unittest.TestCase):
    def test_every_authored_override_keeps_last_matching_predicate_semantics(self):
        variants=0
        for path in MODELS.rglob('*.json'):
            original=json.loads(path.read_text(encoding='utf8'))
            overrides=original.get('overrides',[])
            if not overrides:continue
            variants+=1;ref='nirncraft:item/'+path.relative_to(MODELS).with_suffix('').as_posix()
            native=convert(ref,original)['model']
            keys=sorted({key for override in overrides for key in override['predicate']})
            samples={key:{0.,1.} for key in keys}
            for override in overrides:
                for key,value in override['predicate'].items():
                    if key not in ('charged','firework','pulling','blocking','cast'):samples[key].update((max(0.,value-.0001),value,value+.0001))
            for values in itertools.product(*(sorted(samples[k]) for k in keys)):
                state=dict(zip(keys,values));expected=ref
                if state.get('firework',0)>state.get('charged',0):continue # A rocket is necessarily a charged crossbow.
                for override in overrides:
                    if all(state.get(k,0)>=v for k,v in override['predicate'].items()):expected=override['model']
                self.assertEqual(expected,resolve(native,state),(path.name,state))
        self.assertEqual(74,variants)
    def test_all_112_active_spawn_egg_palettes_are_preserved(self):
        palettes=egg_palettes();self.assertEqual(112,len(palettes))
        for name,colors in palettes.items():
            native=json.loads((OUT/(name+'.json')).read_text())['model']
            self.assertEqual(colors,tuple(tint['value'] for tint in native['tints']),name)
            geometry=json.loads((MODELS/(name+'.json')).read_text())
            self.assertEqual('nirncraft:item/template_nirncraft_spawn_egg',geometry['parent'])
    def test_leaf_and_treasure_tints_survive_component_migration(self):
        for name,color in [('hist_leaves',0x5a7a3a),('graht_oak_leaves',0x4e9a32)]:
            self.assertEqual(color,json.loads((OUT/(name+'.json')).read_text())['model']['tints'][0]['value'])
        treasure=json.loads((OUT/'regional_treasure.json').read_text())['model']['tints']
        self.assertEqual('nirncraft:treasure',treasure[0]['type'])
        self.assertEqual(0xffffff,treasure[1]['value'])
    def test_native_moon_atlas_overrides_suppress_all_eight_phases(self):
        import struct,zlib
        base=MODELS.parents[2]/'minecraft/textures/environment'
        phases=('first_quarter','full_moon','new_moon','third_quarter','waning_crescent','waning_gibbous','waxing_crescent','waxing_gibbous')
        legacy=(base/'moon_phases.png').read_bytes()
        offset=8;compressed=b''
        while offset<len(legacy):
            length=struct.unpack('>I',legacy[offset:offset+4])[0];kind=legacy[offset+4:offset+8]
            if kind==b'IDAT':compressed+=legacy[offset+8:offset+8+length]
            offset+=12+length
        self.assertEqual({0},set(zlib.decompress(compressed)),"All RGBA channels and row filters in the authored blank are zero")
        for phase in phases:self.assertEqual(legacy,(base/'celestial/moon'/(phase+'.png')).read_bytes(),phase)

    def test_every_native_item_model_reference_exists(self):
        def visit(value):
            if isinstance(value,dict):
                if value.get('type')=='minecraft:model':
                    ref=value['model'];namespace,name=ref.split(':',1)
                    if namespace=='nirncraft':self.assertTrue((MODELS.parent/(name+'.json')).is_file(),ref)
                for child in value.values():visit(child)
            elif isinstance(value,list):
                for child in value:visit(child)
        for path in OUT.rglob('*.json'):visit(json.loads(path.read_text(encoding='utf8')))
if __name__=='__main__':unittest.main()
