"""Shared QA identities from authored regions and actual dimension biome-source roles.

Selection and inventory do not establish that a site has generated or passed a test.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def registry(root=ROOT):
    resources = root / 'mod/src/main/resources'
    regions = [{**r, 'dimension': 'minecraft:overworld', 'kind': 'authored_region',
                'source': 'skycraft/tamriel/regions.json'}
               for r in read(resources / 'skycraft/tamriel/regions.json')]
    beyond_path = root / 'mod/src/main/java/com/skycraft/tamriel/terrain/Beyond.java'
    beyond = beyond_path.read_text(encoding='utf-8')
    policy = re.search(r'public static final boolean ENDLESS_LANDS_ENABLED = (true|false);', beyond)
    if policy is None:raise ValueError('Cannot identify active outer-world geography policy')
    archetypes=[]
    if policy[1]=='true':
        start = beyond.index('enum Archetype {')
        declarations = beyond[start:beyond.index('final Regions.Terrain', start)]
        archetypes = re.findall(r'^\s+([A-Z_]+)\([^\n]*"([^"]+)",\s*"([^"]+)"', declarations, re.M)
        if not archetypes or len(archetypes) != len(re.findall(r'^\s+[A-Z_]+\(', declarations, re.M)):
            raise ValueError('Cannot read complete Endless archetype coverage contract')
    else:
        regions.append({'id':'ocean/outer_ocean','province':'ocean','scope':'ocean_wrap',
                        'name':'Outer ocean and opposite-edge travel','dimension':'minecraft:overworld',
                        'kind':'outer_ocean','expectedRegion':'sea/open_ocean','minimumAbsCoordinate':32768,
                        'source':beyond_path.relative_to(root).as_posix(),
                        'scouting':'Real deep-ocean view beyond fixed lands; verify east/west/north/south/corner and mounted wrap separately.',
                        'excludedSystems':{s:'Procedural expansion land is disabled; this region is open ocean.'
                                           for s in ('roads','settlements','npcs')}})
    for name, culture, weather in archetypes:
        regions.append({'id': 'endless/' + name.lower(), 'province': 'realm', 'scope': 'endless',
                        'name': name.title() + ' procedural lands', 'kind': 'procedural_archetype',
                        'dimension': 'minecraft:overworld', 'archetype': name.lower(), 'culture': culture,
                        'weather': weather, 'palette': 'realm/' + name.lower(),
                        'source': beyond_path.relative_to(root).as_posix(),
                        'scouting': 'Select an actual generated realm/xN_zN/site region of this archetype; no fixed coordinate inferred.'})
    for path in sorted((resources / 'data').glob('*/dimension/*.json')):
        dimension = path.parent.parent.name + ':' + path.stem
        definition = read(path)
        source = definition.get('generator', {}).get('biome_source', {})
        if source.get('type') == 'skycraft:realm':
            realm = source['realm']
            for role, biome in source['biomes'].items():
                regions.append({'id': realm + '/' + role, 'province': realm, 'name': role.replace('_', ' ').title(),
                                'dimension': dimension, 'kind': 'realm_role', 'biomes': [biome],
                                'source': path.relative_to(resources).as_posix()})
        elif dimension == 'skycraft:jail':
            regions.append({'id': 'jail/cells', 'province': 'jail', 'name': 'Instanced jail cells',
                            'dimension': dimension, 'kind': 'interior_dimension',
                            'biomes': [definition['generator']['settings']['biome']],
                            'source': path.relative_to(resources).as_posix(),
                            'excludedSystems': {s: 'Jail is an enclosed, generated cell dimension; no outdoor world system.'
                                                for s in ('roads', 'flora', 'fauna', 'settlements', 'weather')}})
        else:
            raise ValueError('Dimension has no QA coverage policy: ' + dimension)
    ids = [r['id'] for r in regions]
    if len(ids) != len(set(ids)):
        raise ValueError('Duplicate QA region identity')
    return regions


def applies(region, system):
    return system not in region.get('excludedSystems', {})
