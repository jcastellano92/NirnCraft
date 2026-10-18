"""Composable world QA: fast audits, regional plans, live evidence, explicit review.

Use `list`, `plan`, `run --tier fast`, `capture CAMERA`, `review RUN CASE`, and
`report RUN`. A fast audit never substitutes for runtime or visual acceptance.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
SPEC = Path(__file__).with_name('world-tests.json')
OUT = ROOT / 'dist/world-tests'


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
    os.replace(tmp, path)


def module(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def fingerprint(patterns):
    digest = hashlib.sha256()
    files = sorted({p for pattern in patterns for p in ROOT.glob(pattern) if p.is_file()})
    for path in files:
        digest.update(path.relative_to(ROOT).as_posix().encode())
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def selected(values, selector):
    if not selector or selector == 'all':
        return list(values)
    names = selector.split(',')
    result = [v for v in values if v['id'] in names or v.get('province') in names]
    found = {v['id'] for v in result} | {v.get('province') for v in result}
    missing = set(names) - found
    if missing:
        raise ValueError('Unknown selection: ' + ', '.join(sorted(missing)))
    return result


def verdict(check, code, output):
    failures = re.findall(check.get('failureRegex', r'(?m)^ERR(?:OR)?\b.*'), output)
    missing = [pattern for pattern in check.get('requireRegex', []) if not re.search(pattern, output)]
    return 'failed' if code != 0 or failures or missing else 'passed'


def load_checks(spec, systems, tier='fast'):
    ids = {s['id'] for s in systems}
    return [c for c in spec['checks'] if ids.intersection(c['systems']) and c.get('tier','fast') == tier]


def run_runtime(spec, systems, regions):
    live = module('world_test_live', 'tools/dev/live_world.py')
    state = live.require_ready()
    if state['inbox']:
        raise ValueError('Preview server queue is busy')
    path, data = new_run('runtime', systems, regions)
    identity=live.read_json(live.DEV/'outbox/runtime.json')
    if identity.get('pid')!=state['client'].get('pid'):
        raise ValueError('Runtime identity does not match active preview process')
    data['runtimeIdentity']=identity
    if not load_checks(spec,systems,'runtime'):
        raise ValueError('No runtime checks registered for these systems; this is a coverage gap, not a pass')
    import contextlib
    import io
    for check in load_checks(spec, systems, 'runtime'):
        started=time.monotonic()
        output=io.StringIO()
        code=0
        try:
            with contextlib.redirect_stdout(output):
                live.submit(check['commands'], timeout=check.get('timeoutSeconds',180))
        except Exception as error:
            output.write('\n'+str(error)); code=1
        text=output.getvalue()
        (path/(check['id']+'.log')).write_text(text,encoding='utf-8')
        data['results'].append({'id':check['id'],'tier':'runtime','systems':check['systems'],
            'receipt':identity,
            'status':verdict(check,code,text),'scope':check.get('scope','runtime contracts, not regional playthrough'),
            'seconds':round(time.monotonic()-started,3),'log':str(path/(check['id']+'.log'))})
        write(path/'run.json',data)
        print(data['results'][-1]['status'].upper(),check['id'],flush=True)
    report(path)
    return any(r['status']=='failed' for r in data['results'])


def measure(camera, systems, regions, frames, mode):
    live=module('world_test_live','tools/dev/live_world.py')
    cameras=module('world_test_cameras','tools/qa/visual_cameras.py')
    saved=read(cameras.PRESETS)['cameras'][camera]
    live.require_ready(); live.live_actions(['identity'])
    if not cameras.same_generation(saved,read(live.GAME/'world-preview/camera-identity.json')):
        raise ValueError('Stale camera: re-plan, scout and approve')
    path,data=new_run('performance',systems,regions)
    tag=data['id']+'-'+camera
    pose=' '.join(map(str,saved['position']+saved['lookAt']))
    result=None
    try:
        live.live_actions(['shaders '+mode,'camera '+pose,'settle 1800','measure '+tag+' '+str(frames)],timeout=300)
        result=read(live.GAME/'build/autotest-performance'/(tag+'.json'))
        write(path/'samples.json',result)
        cameras.validate_receipt(result)
        if result['summary']['samples']!=frames or result.get('shaders') is not (mode=='on'):
            raise ValueError('Incomplete sample or wrong actual shader mode')
        data['results'].append({'id':camera,'tier':'performance','status':'measured',
            'summary':result['summary'],'pacingLimited':result['pacingLimited'],
            'systems':saved['systems'],'region':saved['region'],'evidence':str(path/'samples.json')})
        data['limits']=['Measured is not a performance acceptance pass. Set a hardware/settings budget or compare matching conditions.',result['note']]
    except Exception as error:
        data['results'].append({'id':camera,'tier':'performance','status':'failed',
            'systems':saved['systems'],'region':saved['region'],'error':str(error),
            'evidence':str(path/'samples.json') if result else None})
    write(path/'run.json',data); report(path)
    return int(any(r['status']=='failed' for r in data['results']))


def new_run(kind, systems, regions):
    name = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-' + uuid.uuid4().hex[:6]
    path = OUT / name
    data = {'version': 1, 'id': name, 'kind': kind, 'started': time.time(),
            'systems': [s['id'] for s in systems], 'regions': [r['id'] for r in regions],
            'results': [], 'limits': ['Fast audits are not in-world visual or behavior checks.']}
    write(path / 'run.json', data)
    return path, data


def run_fast(spec, systems, regions, resume=None):
    if not load_checks(spec,systems):raise ValueError('No fast checks selected; not a pass')
    path, data = new_run('fast', systems, regions)
    prior = read(Path(resume) / 'run.json') if resume else {'results': []}
    prior_results = {r['id']: r for r in prior['results']}
    hashes = {}
    for check in load_checks(spec, systems):
        inputs = check.get('inputs', spec['defaultInputs'])
        patterns=tuple(inputs + ['tools/qa/world-tests.json', 'tools/qa/world_test.py'])
        if patterns not in hashes: hashes[patterns]=fingerprint(patterns)
        key=hashes[patterns]
        previous = prior_results.get(check['id'], {})
        if previous.get('status') == 'passed' and previous.get('inputHash') == key:
            result = {**previous, 'reusedFrom': str(resume)}
        else:
            argv = [sys.executable if part == '{python}' else part for part in check['command']]
            started = time.monotonic()
            print('RUN', check['id'], flush=True)
            try:
                completed = subprocess.run(argv, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                           timeout=check.get('timeoutSeconds', 180))
                output = completed.stdout.decode('utf-8', errors='replace')
                code = completed.returncode
            except subprocess.TimeoutExpired as error:
                output = (error.stdout or b'').decode('utf-8', errors='replace') + '\nTIMEOUT\n'
                code = 124
            log = path / (check['id'] + '.log')
            log.write_text(output, encoding='utf-8')
            result = {'id': check['id'], 'systems': check['systems'], 'scope': check.get('scope', 'global'),
                      'status': verdict(check, code, output), 'exitCode': code,
                      'seconds': round(time.monotonic()-started, 3), 'inputHash': key,
                      'command': argv, 'log': str(log.relative_to(ROOT)), 'tier': 'fast'}
            import shutil
            result['artifacts']=[]
            for relative in check.get('artifacts',[]):
                artifact=ROOT/relative
                if not artifact.is_file():
                    result['status']='failed'
                    result.setdefault('missingArtifacts',[]).append(relative)
                    continue
                target=path/'artifacts'/check['id']/artifact.name
                target.parent.mkdir(parents=True,exist_ok=True)
                shutil.copy2(artifact,target)
                result['artifacts'].append({'path':str(target.relative_to(ROOT)),
                    'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
        data['results'].append(result)
        write(path / 'run.json', data)
        print(result['status'].upper(), result['id'], result.get('seconds'), flush=True)
    data['finished'] = time.time()
    write(path / 'run.json', data)
    report(path)
    return any(r['status'] == 'failed' for r in data['results'])


def plan(spec, systems, regions):
    cameras_path = ROOT / 'tools/qa/visual-cameras.json'
    cameras = read(cameras_path).get('cameras', {}) if cameras_path.exists() else {}
    cases = []
    for region in regions:
        for system in systems:
            poses = [name for name, p in cameras.items() if p.get('region') == region['id']
                     and system['id'] in p.get('systems', [])]
            for condition in system['conditions']:
                cases.append({'id': region['id'] + ':' + system['id'] + ':' + condition,
                              'region': region['id'], 'system': system['id'], 'condition': condition,
                              'cameraCandidates': poses, 'status': 'not_run',
                              'required': ['runtime', 'visual:shaders_on', 'visual:shaders_off', 'performance'],
                              'acceptance': system['acceptance'],
                              'next': 'Reuse approved camera after identity check' if poses else
                                      'Inspect real plans/routes, load region, scout and visually approve camera'})
    write(OUT / 'plan.json', {'version': 1, 'regions': [r['id'] for r in regions], 'cases': cases,
                            'note': 'Coverage obligations, not completed tests. Cases may share evidence only when it actually demonstrates each condition.'})
    print(len(cases), 'regional condition cases ->', OUT / 'plan.json')


def capture(camera, systems, regions, shaders):
    live = module('world_test_live', 'tools/dev/live_world.py')
    cameras = module('world_test_cameras', 'tools/qa/visual_cameras.py')
    saved = read(cameras.PRESETS)['cameras'][camera]
    live.require_ready()
    live.live_actions(['identity'])
    current = live.read_json(live.GAME / 'world-preview/camera-identity.json')
    if not cameras.same_generation(saved, current) or saved.get('review') != 'visually reviewed':
        raise ValueError('Stale or unreviewed camera: re-plan, scout and visually approve for this generation')
    path, data = new_run('visual', systems, regions)
    data['shaderSnapshot']=shader_snapshot(live.GAME)
    pose = ' '.join(map(str, saved['position'] + saved['lookAt']))
    for mode in (['on', 'off'] if shaders == 'both' else [shaders]):
        case = camera + '-shaders-' + mode
        tag = data['id'] + '-' + case
        started = time.monotonic()
        try:
            live.live_actions(['shaders ' + mode, 'camera ' + pose, 'settle 1800', 'capture ' + tag], timeout=240)
            png = cameras.CAPTURES / (tag + '.png')
            deadline = time.monotonic() + 20
            while not png.exists() and time.monotonic() < deadline:
                time.sleep(.1)
            receipt = read(cameras.CAPTURES / (tag + '.json'))
            cameras.validate_receipt(receipt)
            if receipt.get('shaders') is not (mode == 'on'):
                raise ValueError('Actual shader mode differs from requested comparison')
            if not png.exists() or not receipt.get('cameraClear') or not cameras.same_generation(saved, receipt):
                raise ValueError('Missing screenshot or invalid capture receipt')
            import shutil
            shutil.copy2(png, path / (case + '.png'))
            write(path / (case + '.json'), receipt)
            result = {'id': case, 'tier': 'visual', 'status': 'awaiting_review', 'shaderMode': mode,
                      'camera': camera, 'region': saved.get('region'), 'systems': saved.get('systems', []),
                      'seconds': round(time.monotonic()-started, 3), 'evidence': str(path / (case + '.png')),
                      'receipt': receipt, 'planReference': saved['planReference']}
        except Exception as error:
            result = {'id': case, 'tier': 'visual', 'status': 'failed', 'error': str(error)}
        data['results'].append(result)
        write(path / 'run.json', data)
    report(path)
    return int(any(r['status']=='failed' for r in data['results']))


def shader_snapshot(game):
    for name in ('oculus.properties','iris.properties'):
        config=game/'config'/name
        if not config.is_file(): continue
        props=dict(line.split('=',1) for line in config.read_text().splitlines() if '=' in line and not line.startswith('#'))
        pack=props.get('shaderPack','')
        path=game/'shaderpacks'/pack
        if path.is_file():
            import zipfile
            content=hashlib.sha256()
            with zipfile.ZipFile(path) as archive:
                for entry in sorted(archive.namelist()):
                    content.update(entry.encode()); content.update(hashlib.sha256(archive.read(entry)).digest())
            return {'name':pack,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
                    'contentHash':content.hexdigest(),
                    'options':(path.with_name(path.name+'.txt').read_text() if path.with_name(path.name+'.txt').exists() else '')}
    return {'name':None,'note':'No configured shader pack file found'}


def review(path, case, status, notes, conditions=None):
    data = read(path / 'run.json')
    result = next(r for r in data['results'] if r['id'] == case)
    if result['tier'] != 'visual' or result['status'] == 'failed':
        raise ValueError('Only completed visual captures can receive visual review; rerun failed captures')
    evidence = Path(result['evidence'])
    if not evidence.is_file() or not notes.strip():
        raise ValueError('Actual image and concrete review observations required')
    if conditions is not None:
        allowed={c for system in read(SPEC)['systems'] if system['id'] in result.get('systems',[]) for c in system['conditions']}
        if not set(conditions).issubset(allowed):raise ValueError('Conditions must belong to the actual captured systems')
        result['conditions']=conditions
    result.update(status=status, review={'notes': notes, 'at': time.time(),
                                       'imageSha256': hashlib.sha256(evidence.read_bytes()).hexdigest()})
    write(path / 'run.json', data)
    report(path)


def collect(tag, reference, systems, regions):
    """Import an actual scripted UI/sequence endpoint without pretending it was reviewed."""
    if len(regions)!=1: raise ValueError('Collect requires one actual region')
    cameras=module('world_test_cameras','tools/qa/visual_cameras.py')
    receipt=read(cameras.CAPTURES/(tag+'.json'))
    cameras.validate_receipt(receipt)
    source=cameras.CAPTURES/(tag+'.png')
    if not source.is_file(): raise ValueError('Missing real capture')
    import shutil
    path,data=new_run('visual',systems,regions)
    data['shaderSnapshot']=shader_snapshot(cameras.live.GAME)
    shutil.copy2(source,path/(tag+'.png')); write(path/(tag+'.json'),receipt)
    data['results'].append({'id':tag,'tier':'visual','status':'awaiting_review',
        'region':regions[0]['id'],'systems':[s['id'] for s in systems],
        'evidence':str(path/(tag+'.png')),'receipt':receipt,'planReference':reference})
    write(path/'run.json',data); report(path)


def report(path):
    data = read(path / 'run.json')
    rows = data['results']
    counts = {status: sum(r['status'] == status for r in rows) for status in
              ('passed', 'failed', 'rejected', 'awaiting_review', 'not_run', 'measured')}
    text = [f"World test {data['id']} ({data['kind']})", json.dumps(counts),
            'Regions selected: ' + ', '.join(data['regions']),
            'Selection does not imply every regional condition was exercised.', '']
    text += [f"{r['status'].upper():18} {r['id']}" for r in rows]
    text += ['', *data.get('limits', [])]
    (path / 'report.txt').write_text('\n'.join(text)+'\n', encoding='utf-8')
    print('\n'.join(text))
    print('ARTIFACTS', path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--systems', default='all', help='Comma-separated system IDs')
    parser.add_argument('--regions', default='all', help='Region IDs or province IDs')
    sub = parser.add_subparsers(dest='action', required=True)
    sub.add_parser('list')
    sub.add_parser('plan')
    sub.add_parser('inventory')
    sub.add_parser('coverage')
    scene=sub.add_parser('scenario')
    scene.add_argument('file',type=Path)
    scene.add_argument('--shaders',choices=['on','off','both'],default='both')
    scene.add_argument('--variant',default='all')
    run = sub.add_parser('run')
    run.add_argument('--tier', choices=['fast','runtime'], default='fast')
    run.add_argument('--resume', type=Path)
    run.add_argument('--checks',default='all',help='Run specific check IDs, or all checks for the selected systems')
    cap = sub.add_parser('capture')
    cap.add_argument('camera')
    cap.add_argument('--shaders', choices=['on', 'off', 'both'], default='both')
    imported=sub.add_parser('collect')
    imported.add_argument('tag')
    imported.add_argument('--plan',required=True)
    perf=sub.add_parser('measure')
    perf.add_argument('camera')
    perf.add_argument('--frames',type=int,default=600)
    perf.add_argument('--shaders',choices=['on','off'],default='on')
    rev = sub.add_parser('review')
    rev.add_argument('run', type=Path)
    rev.add_argument('case')
    rev.add_argument('--status', choices=['passed', 'rejected'], required=True)
    rev.add_argument('--notes', required=True)
    rev.add_argument('--conditions',help='Comma-separated conditions actually visible; no inferred regional coverage')
    rep = sub.add_parser('report')
    rep.add_argument('run', type=Path)
    args = parser.parse_args()
    spec = read(SPEC)
    regions = selected(read(ROOT / 'mod/src/main/resources/skycraft/tamriel/regions.json'), args.regions)
    systems = selected(spec['systems'], args.systems)
    if args.action == 'list':
        print(json.dumps({'systems': systems, 'checks': load_checks(spec, systems),
                          'regions': [r['id'] for r in regions]}, indent=2))
    elif args.action == 'plan':
        plan(spec, systems, regions)
    elif args.action == 'inventory':
        module('world_inventory','tools/qa/world_inventory.py').inventory()
    elif args.action == 'coverage':
        module('world_coverage','tools/qa/world_coverage.py').run(sys.modules[__name__],spec,systems,regions)
    elif args.action == 'scenario':
        return module('world_scenario','tools/qa/world_scenario.py').run(sys.modules[__name__],args.file,args.shaders,args.variant)
    elif args.action == 'run':
        if args.checks!='all':spec={**spec,'checks':selected(spec['checks'],args.checks)}
        if args.tier=='runtime': return int(run_runtime(spec,systems,regions))
        return int(run_fast(spec, systems, regions, args.resume))
    elif args.action == 'capture':
        return capture(args.camera, systems, regions, args.shaders)
    elif args.action == 'collect':
        collect(args.tag,args.plan,systems,regions)
    elif args.action == 'measure':
        return measure(args.camera,systems,regions,args.frames,args.shaders)
    elif args.action == 'review':
        review(args.run, args.case, args.status, args.notes, args.conditions.split(',') if args.conditions else None)
    else:
        report(args.run)
    return 0


if __name__ == '__main__':
    sys.exit(main())
