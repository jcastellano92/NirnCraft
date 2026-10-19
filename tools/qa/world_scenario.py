"""Reusable live gameplay scenarios: real commands/packets, observed completion and immutable evidence."""
import contextlib
import hashlib
import io
import json
import re
from pathlib import Path
import shutil
import time


def artifact(game,relative):
    path=(game/relative).resolve()
    if not path.is_relative_to(game.resolve()): raise ValueError('Scenario evidence escapes disposable game')
    return path


def assertion(value,check):
    for part in check['field'].split('.'):
        if not isinstance(value,dict) or part not in value:return False
        value=value[part]
    if 'equals' in check:return type(value) is type(check['equals']) and value==check['equals']
    if 'atLeast' in check:return isinstance(value,(float,int)) and not isinstance(value,bool) and value>=check['atLeast']
    raise ValueError('Expected equals or atLeast')


def run(qa,file,modes,variant):
    scene=qa.read(file);world=qa.read(qa.ROOT/'mod/src/main/resources/nirncraft/tamriel/regions.json')
    spec=qa.read(qa.SPEC)
    systems=qa.selected(spec['systems'],','.join(scene['systems']))
    regions=qa.selected(world,scene['region'])
    if len(regions)!=1:raise ValueError('Scenario must name one actual region')
    live=qa.module('scenario_live','tools/dev/live_world.py')
    cameras=qa.module('scenario_cameras','tools/qa/visual_cameras.py')
    state=live.require_ready();live.live_actions(['identity'])
    runtimeIdentity=live.read_json(live.DEV/'outbox/runtime.json')
    if runtimeIdentity.get('pid')!=state['client'].get('pid'):raise ValueError('Scenario runtime identity is stale')
    current=live.read_json(live.GAME/'world-preview/camera-identity.json')
    camera=qa.read(cameras.PRESETS)['cameras'][scene['camera']]
    if camera.get('review')!='visually reviewed' or not cameras.same_generation(camera,current):
        raise ValueError('Stale/unreviewed scenario camera; revisit its actual plan, clear pose and frame first')
    if camera.get('region')!=scene['region']:raise ValueError('Camera/scene region mismatch')
    variants=scene.get('variants',{'default':{}})
    if variant!='all' and variant not in variants:raise ValueError('Unknown scenario variant')
    failures=0
    for name,values in variants.items():
        if variant!='all' and variant!=name:continue
        for mode in (['on','off'] if modes=='both' else [modes]):
            path,data=qa.new_run('scenario',systems,regions)
            data.update(scenario=scene['id'],variant=name,conditions=scene['conditions'],planReference=scene['planReference'],
                        runtimeIdentity=runtimeIdentity,
                        scenarioSha256=hashlib.sha256(Path(file).read_bytes()).hexdigest(),shaderSnapshot=qa.shader_snapshot(live.GAME))
            variables={**values,'run':data['id'],'variant':name}
            expand=lambda text:text.format(**variables)
            log=io.StringIO();success=False
            def checked(commands):
                start=log.tell()
                with contextlib.redirect_stdout(log):live.submit(commands)
                if re.search(scene.get('failureRegex',r'(?mi)^\s*Scenario failed:'),log.getvalue()[start:]):
                    raise ValueError('Scenario command rejected; inspect scenario.log')
            try:
                pose=' '.join(map(str,camera['position']+camera['lookAt']))
                live.live_actions(['shaders '+mode,'camera '+pose,'settle 1800'])
                checked([expand(c) for c in scene.get('setupCommands',[])])
                for step in scene['steps']:
                    if step.get('commands'):checked([expand(c) for c in step['commands']])
                    if step.get('actions'):live.live_actions([expand(a) for a in step['actions']])
                    if step.get('waitFor'):
                        wait=step['waitFor'];deadline=time.monotonic()+wait.get('timeoutSeconds',15)
                        while True:
                            checked([expand(c) for c in wait.get('probeCommands',[])])
                            checks=wait['assertions'];observations=[]
                            for check in checks:
                                source=artifact(live.GAME,expand(check['file']))
                                observations.append(source.exists() and assertion(qa.read(source),check))
                            if all(observations):break
                            if wait.get('whilePendingCommands'):checked([expand(c) for c in wait['whilePendingCommands']])
                            if time.monotonic()>=deadline:raise ValueError('Scenario completion signal timed out: '+str(checks))
                            time.sleep(.1)
                for check in scene.get('assertions',[]):
                    source=artifact(live.GAME,expand(check['file']))
                    value=qa.read(source)
                    if not assertion(value,check):raise ValueError('Scenario assertion failed: '+str(check))
                    target=path/'artifacts'/source.name;target.parent.mkdir(exist_ok=True)
                    shutil.copy2(source,target)
                for tag in scene['captures']:
                    tag=expand(tag);source=cameras.CAPTURES/(tag+'.png');receiptFile=cameras.CAPTURES/(tag+'.json')
                    deadline=time.monotonic()+20
                    while not (source.is_file() and receiptFile.is_file()):
                        if time.monotonic()>=deadline:raise ValueError('Missing scenario capture: '+tag)
                        time.sleep(.1)
                    receipt=qa.read(receiptFile)
                    cameras.validate_receipt(receipt)
                    if receipt.get('shaders') is not (mode=='on') or not cameras.same_generation(camera,receipt):
                        raise ValueError('Scenario capture shader/generation mismatch')
                    target=path/(tag+'.png');shutil.copy2(source,target);qa.write(path/(tag+'.json'),receipt)
                    data['results'].append({'id':tag,'tier':'visual','status':'awaiting_review','region':scene['region'],
                        'systems':scene['systems'],'conditions':scene['conditions'],'evidence':str(target),'receipt':receipt,
                        'planReference':scene['planReference']})
                success=True
            except Exception as error:
                log.write('\nERROR '+str(error));failures+=1
                # A failed behavioral assertion still needs the actual frame and
                # authoritative observations for diagnosis. It cannot be reviewed green.
                try:
                    tag=data['id']+'-failure'
                    live.live_actions(['capture '+tag],cancel=True)
                    source=cameras.CAPTURES/(tag+'.png');deadline=time.monotonic()+20
                    while not source.is_file() and time.monotonic()<deadline:time.sleep(.1)
                    if source.is_file():
                        target=path/(tag+'.png');shutil.copy2(source,target)
                        data['results'].append({'id':tag,'tier':'visual','status':'failed','evidence':str(target),
                                                'error':'Diagnostic from failed scenario; not acceptance'})
                except Exception as diagnostic:log.write('\nDIAGNOSTIC '+str(diagnostic))
            finally:
                for check in scene.get('assertions',[]):
                    source=artifact(live.GAME,expand(check['file']))
                    if source.is_file():
                        target=path/'artifacts'/source.name;target.parent.mkdir(exist_ok=True);shutil.copy2(source,target)
                try:
                    live.live_actions(['cast stop','use up'],cancel=True)
                    with contextlib.redirect_stdout(log):live.submit([expand(c) for c in scene.get('cleanupCommands',[])])
                except Exception as error:
                    log.write('\nCLEANUP ERROR '+str(error));success=False;failures+=1
                (path/'scenario.log').write_text(log.getvalue(),encoding='utf-8')
                data['results'].append({'id':scene['id']+'-'+name+'-runtime','tier':'runtime',
                    'receipt':runtimeIdentity,
                    'status':'passed' if success else 'failed','region':scene['region'],'systems':scene['systems'],
                    'conditions':scene['conditions'],'log':str(path/'scenario.log'),
                    'scope':scene.get('scope','Explicit scenario assertions; not a full regional playthrough')})
                qa.write(path/'scenario.json',scene);qa.write(path/'run.json',data);qa.report(path)
    return int(bool(failures))
