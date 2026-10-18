"""Conservative condition ledger: reviewed current evidence, never selected-region coverage."""
import hashlib
import json
import math
from pathlib import Path


def evidence_state(row, identity, core, shader_hash, root, dimension=None):
    if row.get('status') != 'passed':
        return row.get('status', 'not_run')
    receipt = row.get('receipt', {})
    if receipt.get('coreSha256') != core:
        return 'stale_core'
    if row.get('tier') != 'visual':
        return 'contract_only'
    expected = {**identity, 'dimension': dimension or identity.get('dimension')}
    if any(receipt.get(k) != expected.get(k) or expected.get(k) is None
           for k in ('seed', 'dimension', 'generationInputs')):
        return 'stale_generation'
    if receipt.get('shaders') and (not shader_hash or row.get('_shaderHash') != shader_hash):
        return 'stale_shader'
    if not (receipt.get('cameraClear') and receipt.get('readinessPassed')
            and receipt.get('nightVision') is False):
        return 'invalid_receipt'
    image = Path(row.get('evidence', ''))
    if not image.is_absolute():
        image = root / image
    review = row.get('review', {})
    if not image.is_file() or review.get('imageSha256') != hashlib.sha256(image.read_bytes()).hexdigest():
        return 'unverified_image'
    return 'current_visual'


def shader_identity(snapshot):
    if not snapshot.get('contentHash'):return None
    options=sorted(line.strip() for line in snapshot.get('options','').splitlines()
                   if line.strip() and not line.lstrip().startswith('#'))
    return hashlib.sha256((snapshot['contentHash']+'\n'+'\n'.join(options)).encode()).hexdigest()


def region_evidence_state(region, receipt):
    kind=region.get('kind')
    if kind=='authored_region':
        return None if receipt.get('regionId')==region['id'] else 'unverified_region'
    if kind=='procedural_archetype':
        return None if (receipt.get('regionArchetype')==region['archetype']
                        and receipt.get('regionId','').startswith('realm/')) else 'unverified_archetype'
    if kind=='outer_ocean':
        position=receipt.get('position',[])
        if (receipt.get('regionId')!=region['expectedRegion'] or not isinstance(position,(list,tuple)) or len(position)!=3
                or any(isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) for v in position)
                or max(abs(position[0]),abs(position[2]))<region['minimumAbsCoordinate']):
            return 'unverified_outer_ocean'
        return None
    if kind in ('realm_role','interior_dimension'):
        return None if receipt.get('biomeId') in region.get('biomes',[]) else 'unverified_biome_role'
    return None


def build(spec, regions, runs, identity, core, shader_hash, root):
    index = {}
    observations = []
    by_region = {r['id']: r for r in regions}
    for run in runs:
        for source in run.get('results', []):
            row = dict(source)
            row['_shaderHash'] = shader_identity(run.get('shaderSnapshot', {}))
            region = by_region.get(row.get('region'), {})
            state = evidence_state(row, identity, core, shader_hash, root, region.get('dimension'))
            if state=='current_visual':
                state=region_evidence_state(region,row.get('receipt',{})) or state
            entry = {'run': run['id'], 'case': row['id'], 'state': state,
                     'scenario':run.get('scenario'), 'variant':run.get('variant')}
            if row.get('region') and row.get('conditions'):
                for system in row.get('systems', []):
                    for condition in row['conditions']:
                        key = (row['region'], system, condition)
                        index.setdefault(key, []).append({**entry, 'shaders': row.get('receipt', {}).get('shaders')})
            else:
                observations.append(entry)
    cases = []
    for region in regions:
        for system in spec['systems']:
            if system['id'] in region.get('excludedSystems', {}):
                continue
            for condition in system['conditions']:
                evidence = index.get((region['id'], system['id'], condition), [])
                modes = {e['shaders'] for e in evidence if e['state'] == 'current_visual'}
                cases.append({'region': region['id'], 'dimension': region.get('dimension', 'minecraft:overworld'),
                              'system': system['id'], 'condition': condition,
                              'visual': 'both_modes_reviewed' if modes == {False, True} else
                                        'one_mode_reviewed' if modes else 'missing_current_visual',
                              'evidence': evidence,
                              'acceptance': 'not_signed_off'})
    return {'version': 2, 'identity': identity, 'coreSha256': core, 'cases': cases,
            'excludedSystems': [{'region': r['id'], 'system': s, 'reason': why}
                                for r in regions for s, why in r.get('excludedSystems', {}).items()],
            'observationsWithoutConditionCoverage': observations,
            'limits': ['Visual evidence does not prove behavior, performance budgets or full route usability.',
                       'Both shader modes are required for visual samples; examples do not prove all variants or full condition acceptance.',
                       'An active runtime identity does not prove existing chunks were regenerated after content changes.']}


def run(qa, spec, systems, regions):
    live = qa.module('coverage_live', 'tools/dev/live_world.py')
    live.require_ready()
    live.live_actions(['identity'])
    identity = qa.read(live.GAME / 'world-preview/camera-identity.json')
    runtime = qa.read(live.DEV / 'outbox/runtime.json')
    shader = qa.shader_snapshot(live.GAME)
    runs = [qa.read(p) for p in sorted(qa.OUT.glob('*/run.json'))]
    selected_spec = {**spec, 'systems': systems}
    result = build(selected_spec, regions, runs, identity, runtime['coreSha256'],
                   shader_identity(shader), qa.ROOT)
    qa.write(qa.OUT / 'coverage.json', result)
    counts = {status: sum(c['visual'] == status for c in result['cases']) for status in
              ('both_modes_reviewed', 'one_mode_reviewed', 'missing_current_visual')}
    print(json.dumps(counts, indent=2))
    print('Coverage ledger:', qa.OUT / 'coverage.json')
    print('No condition automatically signed off. Unlabelled captures remain observations.')
