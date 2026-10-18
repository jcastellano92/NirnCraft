"""Archive an explicitly scoped, verified milestone with its real QA evidence.

Manifest: name, scope, coreJar, buildLog, junitDirectory, requiredRuns[{path,checks}],
evidenceFiles[], knownFindings[]. This does not imply all-world release acceptance.
"""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[2]


def read(path): return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def digest(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def build_text(path):
    raw=Path(path).read_bytes()
    return raw.decode('utf-16' if raw.startswith((b'\xff\xfe',b'\xfe\xff')) else 'utf-8',errors='replace')


def validate(manifest):
    if not manifest.get('scope') or not manifest.get('requiredRuns'):
        raise ValueError('Concrete scope and required evidence runs are mandatory')
    core=Path(manifest['coreJar']); core_hash=digest(core)
    log=build_text(manifest['buildLog'])
    if 'BUILD SUCCESSFUL' not in log or 'BUILD FAILED' in log:
        raise ValueError('A successful full build log is required')
    tests=failures=skips=0
    for path in Path(manifest['junitDirectory']).glob('TEST-*.xml'):
        suite=ET.parse(path).getroot()
        tests+=int(suite.get('tests',0)); failures+=int(suite.get('failures',0))+int(suite.get('errors',0)); skips+=int(suite.get('skipped',0))
    if not tests or failures or skips: raise ValueError('JUnit must contain actual passing tests without failures/skips')
    runs=[]; visual=0; shader_hashes=set()
    module=importlib.util.spec_from_file_location('milestone_world_test',ROOT/'tools/qa/world_test.py')
    qa=importlib.util.module_from_spec(module);module.loader.exec_module(qa)
    spec=read(ROOT/'tools/qa/world-tests.json');checks={c['id']:c for c in spec['checks']};hashes={}
    for file in manifest.get('evidenceFiles',[]):
        if not Path(file).is_file(): raise ValueError('Missing evidence: '+file)
    for requirement in manifest['requiredRuns']:
        folder=Path(requirement['path']); run=read(folder/'run.json')
        results={r['id']:r for r in run['results']}
        ids=requirement.get('checks') or list(results)
        if not ids: raise ValueError('An empty run is not evidence')
        for check in ids:
            result=results.get(check,{})
            if result.get('status')!='passed': raise ValueError('Required check not passed: '+check)
            if result.get('tier')=='fast':
                definition=checks.get(check)
                if definition is None:raise ValueError('Required fast check no longer registered: '+check)
                patterns=tuple(definition.get('inputs',spec['defaultInputs'])+['tools/qa/world-tests.json','tools/qa/world_test.py'])
                if patterns not in hashes:hashes[patterns]=qa.fingerprint(patterns)
                if result.get('inputHash')!=hashes[patterns]:raise ValueError('Fast evidence is stale: '+check)
            if result.get('tier')=='runtime' and result.get('receipt',{}).get('coreSha256')!=core_hash:
                raise ValueError('Runtime evidence does not match exact staged core: '+check)
            if result.get('tier')=='visual':
                if result.get('receipt',{}).get('coreSha256')!=core_hash:
                    raise ValueError('Visual evidence does not match the exact staged core: '+check)
                image=Path(result['evidence'])
                if not image.is_file() or digest(image)!=result.get('review',{}).get('imageSha256'):
                    raise ValueError('Visual image missing or changed after review: '+check)
                visual+=1
                if result.get('receipt',{}).get('shaders'):
                    snapshot=run.get('shaderSnapshot',{})
                    if not snapshot.get('contentHash'): raise ValueError('Missing tested shader content identity')
                    shader_hashes.add(snapshot['contentHash'])
        runs.append(folder)
    if not visual: raise ValueError('A milestone needs actual reviewed in-world visuals')
    return {'coreSha256':core_hash,'tests':tests,'requiredRuns':[str(p) for p in runs],
            'reviewedVisuals':visual,'shaderContentHashes':sorted(shader_hashes),'scope':manifest['scope']}


def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('manifest',type=Path)
    parser.add_argument('--validate-only',action='store_true'); args=parser.parse_args()
    manifest=read(args.manifest); proof=validate(manifest)
    name=manifest['name']
    if not name or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in name):
        raise ValueError('Use a simple immutable milestone name')
    target=ROOT/'releases'/(name+'.zip')
    if args.validate_only: print(json.dumps(proof,indent=2)); return
    if target.exists(): raise ValueError('Milestone exists; choose a new immutable name')
    stage=(ROOT/'dist/milestones'/name).resolve()
    if not stage.is_relative_to(ROOT.resolve()): raise ValueError('Stage outside workspace')
    stage.mkdir(parents=True,exist_ok=True)
    frozen=stage/'skycraft-core.jar'; shutil.copy2(manifest['coreJar'],frozen)
    if digest(frozen)!=proof['coreSha256']: raise ValueError('Core changed while staging')
    # Fixed builder scratch path must remain inside this repository before its cleanup runs.
    scratch=(ROOT/'dist/tmp_prism_build').resolve()
    if not scratch.is_relative_to(ROOT.resolve()): raise ValueError('Unsafe package scratch directory')
    with (stage/'package.log').open('wb') as log:
        result=subprocess.run([sys.executable,str(ROOT/'tools/build_prism_instance.py'),'--core-jar',str(frozen)],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode: raise ValueError('Instance packaging failed; inspect '+str(stage/'package.log'))
    target.parent.mkdir(parents=True,exist_ok=True)
    pending=target.with_suffix('.zip.pending')
    shutil.copy2(ROOT/'dist/Skycraft-client-instance.zip',pending)
    proof['knownFindings']=manifest.get('knownFindings',[])
    with zipfile.ZipFile(pending,'a',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('Skycraft/QA/milestone.json',json.dumps(proof,indent=2)+'\n')
        archive.writestr('Skycraft/QA/manifest.json',json.dumps(manifest,indent=2)+'\n')
        for requirement in manifest['requiredRuns']:
            folder=Path(requirement['path'])
            for file in folder.rglob('*'):
                if file.is_file(): archive.write(file,'Skycraft/QA/runs/'+folder.name+'/'+file.relative_to(folder).as_posix())
        for index,source in enumerate(manifest.get('evidenceFiles',[])):
            file=Path(source)
            if not file.is_file(): raise ValueError('Missing evidence: '+str(file))
            archive.write(file,'Skycraft/QA/evidence/'+str(index)+'-'+file.name)
        archive.write(manifest['buildLog'],'Skycraft/QA/build.log')
    with zipfile.ZipFile(pending) as archive:
        if hashlib.sha256(archive.read('Skycraft/.minecraft/mods/skycraft-core.jar')).hexdigest()!=proof['coreSha256']:
            raise ValueError('Packaged core does not match tested core')
        if archive.testzip() is not None: raise ValueError('Archive CRC verification failed')
        if proof['shaderContentHashes']:
            shaders=archive.read('Skycraft/.minecraft/shaderpacks/ComplementaryReimagined_r5.9.3.zip')
            content=hashlib.sha256()
            with zipfile.ZipFile(io.BytesIO(shaders)) as pack:
                for entry in sorted(pack.namelist()):
                    content.update(entry.encode()); content.update(hashlib.sha256(pack.read(entry)).digest())
            if proof['shaderContentHashes']!=[content.hexdigest()]:
                raise ValueError('Packaged shader contents differ from reviewed visual evidence')
    os.replace(pending,target)
    proof.update(archive=str(target),sha256=digest(target))
    (stage/'receipt.json').write_text(json.dumps(proof,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(proof,indent=2))


if __name__=='__main__': main()
