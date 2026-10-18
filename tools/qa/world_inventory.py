"""Build real regional scouting inputs from loaded content indices and the authored raster."""
import json
import importlib.util
from pathlib import Path
from collections import Counter
import numpy as np
from PIL import Image

ROOT=Path(__file__).resolve().parents[2]
TAM=ROOT/'mod/src/main/resources/skycraft/tamriel'


def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))


def resolved_palette(palette,palettes,seen=None):
    seen=set() if seen is None else seen
    if palette in seen or palette not in palettes: return {'contexts': {}, 'micro': []}
    seen.add(palette); row=palettes[palette]
    parent=resolved_palette(row.get('fallback'),palettes,seen)
    return {'contexts': {**parent['contexts'], **row.get('contexts', {})},
            'micro': row.get('micro', parent['micro'])}


def biomes(palette,palettes,seen=None):
    effective=resolved_palette(palette,palettes,seen)
    result={v['biome'] for values in effective['contexts'].values() for v in values}
    result.update(m['biome'] for m in effective['micro'] if m.get('biome'))
    return result


def inventory():
    loader=importlib.util.spec_from_file_location('inventory_regions',Path(__file__).with_name('world_regions.py'))
    registry=importlib.util.module_from_spec(loader);loader.loader.exec_module(registry)
    all_regions=registry.registry(ROOT)
    regions=[r for r in all_regions if r['kind']=='authored_region']
    palettes=read(TAM/'palettes.json')['palettes']
    raster=np.asarray(Image.open(TAM/'regions.png'))
    elevation=np.asarray(Image.open(TAM/'elevation.png')).astype(np.float32)/32-64
    meta=read(ROOT/'tools/worldgen/tamriel_vectors.json')['meta']
    places=[]
    for name in read(TAM/'places/index.json'):
        places.extend(read(TAM/'places'/name))
    city_plans=set(read(TAM/'cityplans/index.json')['plans'])
    rows=[]
    for region in regions:
        owned=[p for p in places if p.get('region')==region['id']]
        counts=Counter(p['type'] for p in owned)
        candidates=[]
        for kind in ('capital','city','town','village','dungeon_fixed','cave','fort','landmark','harbour'):
            choices=sorted((p for p in owned if p['type']==kind),key=lambda p:p['id'])
            for p in choices[:2]:
                candidates.append({'place':p['id'],'type':kind,'xz':[p['x'],p['z']],
                    'radius':p.get('radius'),'cityPlan':p['id'] in city_plans,
                    'source':'skycraft/tamriel/places/index.json','why':p.get('why',[]),
                    'status':'plan candidate; actual loaded geometry and camera review required'})
        ys,xs=np.nonzero(raster==region['index'])
        if len(xs):
            # A centroid can fall in another region or the sea. Pick an actual region cell.
            nearest=int(np.argmin((xs-float(np.mean(xs)))**2+(ys-float(np.mean(ys)))**2))
            col,row=int(xs[nearest]),int(ys[nearest])
            candidates.append({'type':'regional_raster_sample','xz':[meta['min_x']+(col+.5)*meta['cell'],
                meta['min_z']+(row+.5)*meta['cell']],'estimatedMacroHeight':float(elevation[row,col]),
                'source':'skycraft/tamriel/regions.png','status':'unloaded scout candidate, not a camera height'})
        rows.append({'id':region['id'],'province':region['province'],'dimension':region['dimension'],
                     'kind':region['kind'],'weatherProfile':region['weather'],
                     'terrain':region.get('terrain',{}),'placeTypes':dict(counts),'places':len(owned),
                     'biomes':sorted(biomes(region['palette'],palettes)),
                     'microRules':len(resolved_palette(region['palette'],palettes)['micro']),
                     'candidates':candidates,'rasterCells':len(xs),
                     'visualStatus':'not established by inventory'})
    for region in all_regions:
        if region['kind']=='authored_region': continue
        if region['kind']=='procedural_archetype':
            rows.append({**region,'candidates':[], 'biomes':sorted(biomes(region['palette'],palettes)),
                         'microRules':len(resolved_palette(region['palette'],palettes)['micro']),
                         'visualStatus':'not established by inventory'})
            continue
        rows.append({**region,'candidates':[], 'visualStatus':'not established by inventory',
                     'scouting':'Use actual RealmLayout/realm site or jail cell plan; no raster/Overworld weather inference.'})
    out=ROOT/'dist/world-tests/inventory.json'; out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps({'regions':rows,'places':len(places),
        'note':'Authored data inventory; not runtime generation, collision, navigation or visual acceptance.'},indent=2)+'\n',encoding='utf-8')
    print(f'{len(rows)} regions, {len(places)} indexed places -> {out}')
    return rows


if __name__=='__main__': inventory()
