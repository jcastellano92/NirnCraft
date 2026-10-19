"""Exercise actual default-distance ocean crossings in the disposable preview. Images require visual review."""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import shutil
import time

ROOT=Path(__file__).resolve().parents[2]
NUMBER=r'([-+0-9.eE]+)[dDfF]?'
POSITION=re.compile(r'\[\s*'+NUMBER+r'\s*,\s*'+NUMBER+r'\s*,\s*'+NUMBER+r'\s*\]')
FIXTURE='nirncraft_ocean_wrap_qa'


def entity_position(output):
    matches=POSITION.findall(output)
    if not matches:raise ValueError('Missing actual entity position in command output')
    return [float(v) for v in matches[-1]]


def crossing_cases(half):
    return [('east',(half+8,100,1024),(-half+8,100,1024)),
            ('west',(-half-8,100,1024),(half-8,100,1024)),
            ('south',(1024,100,half+8),(1024,100,-half+8)),
            ('north',(1024,100,-half-8),(1024,100,half-8)),
            ('corner',(half+8,100,half+12),(-half+8,100,-half+12))]


def coordinates(values):
    # Minecraft centers integer X/Z command arguments on blocks. Explicit decimals preserve the tested overshoot.
    return ' '.join(str(float(value)) for value in values)


def effective_config(game, world=None):
    """Mirror native SYNCED config selection; defaultconfigs only seeds the selected file."""
    game=Path(game)
    if world is None:
        saves=[p for p in (game/'saves').iterdir() if p.is_dir() and (p/'level.dat').is_file()]
        if len(saves)!=1:raise ValueError('Specify --world SAVE_FOLDER to select the actual loaded save')
        save=saves[0]
    else:
        save=game/'saves'/world
        if not (save/'level.dat').is_file():raise ValueError('Selected save has no level.dat: '+str(save))
    override=save/'syncedconfig/nirncraft-ocean-server.toml'
    selected=override if override.is_file() else game/'config/nirncraft-ocean-server.toml'
    if not selected.is_file():raise ValueError('No native ocean configuration at '+str(selected))
    return selected


def run(output, world=None, shaders='current'):
    spec=importlib.util.spec_from_file_location('ocean_live',ROOT/'tools/dev/live_world.py')
    live=importlib.util.module_from_spec(spec);spec.loader.exec_module(live)
    live.require_ready()
    output.mkdir(parents=True,exist_ok=True)
    if (output/'run.json').exists():raise ValueError('Use a new output directory to preserve evidence')
    config=effective_config(live.GAME,world)
    text=config.read_text()
    match=re.search(r'^\s*halfWidth\s*=\s*(\d+)\s*$',text,re.M)
    if not match or not re.search(r'^\s*wrapEnabled\s*=\s*true\s*$',text,re.M):
        raise ValueError('Cannot verify actual enabled ocean configuration')
    half=int(match[1]);runtime=live.read_json(live.DEV/'outbox/runtime.json')
    iris=live.GAME/'config/iris.properties'
    properties=dict(line.split('=',1) for line in iris.read_text().splitlines() if '=' in line and not line.startswith('#')) if iris.is_file() else {}
    shader=live.GAME/'shaderpacks'/properties.get('shaderPack','')
    result={'runtime':runtime,'halfWidth':half,'configPath':str(config),'config':text,'results':[],
            'shaderSha256':hashlib.sha256(shader.read_bytes()).hexdigest() if shader.is_file() else None,
            'limits':'Actual server crossing and client readiness; images await explicit review. No GPU throughput claim.'}
    origin=None;made_fixture=False;creative=False;shader_changed=False
    tag='ocean-'+str(time.time_ns())
    def save():live.atomic(output/'run.json',(json.dumps(result,indent=2)+'\n').encode())
    def command(lines,name):
        with (output/(name+'.log')).open('w',encoding='utf8') as log,contextlib.redirect_stdout(log):
            live.submit(lines,timeout=180)
    def position(selector):
        log=io.StringIO()
        with contextlib.redirect_stdout(log):live.submit(['data get entity '+selector+' Pos'],timeout=180)
        return entity_position(log.getvalue())
    def wait_position(selector,expected,tolerance=.25):
        deadline=time.monotonic()+90
        while True:
            actual=position(selector)
            if all(abs(a-b)<tolerance for a,b in zip(actual,expected)):return actual
            if time.monotonic()>deadline:raise ValueError('Crossing did not reach expected coordinates: '+str(actual))
            time.sleep(.1)
    def capture(name):
        current=tag+'-'+name
        with (output/(name+'-client.log')).open('w',encoding='utf8') as log,contextlib.redirect_stdout(log):
            live.live_actions(['settle 2400','capture '+current],timeout=180)
        source=live.GAME/'build/autotest-screenshots'
        receipt=live.read_json(source/(current+'.json'))
        if not (receipt.get('cameraClear') and receipt.get('readinessPassed') and receipt.get('nightVision') is False):
            raise ValueError('Invalid or incompletely loaded image: '+name)
        if receipt.get('dimension')!='minecraft:overworld' or receipt.get('coreSha256')!=runtime['coreSha256']:
            raise ValueError('Capture differs from the actual runtime/dimension')
        for ext in ('png','json'):shutil.copy2(source/(current+'.'+ext),output/(name+'.'+ext))
        return receipt
    save()
    try:
        live.live_actions(['ui close','identity'],timeout=30)
        if live.read_json(live.DEV/'camera-identity.json').get('dimension')!='minecraft:overworld':
            raise ValueError('Start this test in the overworld')
        mode_log=io.StringIO()
        with contextlib.redirect_stdout(mode_log):live.submit(['data get entity @p playerGameType'],timeout=30)
        if not re.search(r'entity data:\s*3\s*$',mode_log.getvalue(),re.M):
            raise ValueError('Start this camera suite in spectator mode')
        origin=capture('origin')
        if origin.get('difficulty')!='peaceful':raise ValueError('Use the isolated peaceful test world for actor fixtures')
        test_shaders=origin.get('shaders') is True if shaders=='current' else shaders=='on'
        result['shaderMode']=test_shaders
        if test_shaders and shaders=='on':
            acceptance=live.read_json(live.GAME/'migration/custom-shader-acceptance.json')
            if acceptance.get('target')!=live.TARGET or acceptance.get('integrationVerified') is not True or not shader.exists():
                raise ValueError('Native custom shader integration is not accepted/installed; use --shaders current or off')
        if test_shaders is not origin.get('shaders'):
            shader_changed=True
            live.live_actions(['shaders '+('on' if test_shaders else 'off')],timeout=180)
        for name,start,expected in crossing_cases(half):
            row={'case':name,'start':start,'expected':expected,'status':'failed'}
            began=time.monotonic()
            try:
                command(['tp @p '+coordinates(start)+' 0 0'],name)
                row['actual']=wait_position('@p',expected)
                row['serverCrossingSeconds']=round(time.monotonic()-began,3)
                receipt=capture(name)
                if receipt.get('shaders') is not test_shaders:raise ValueError('Actual shader mode changed unexpectedly')
                if any(abs(a-b)>.25 for a,b in zip(receipt['position'],expected)):raise ValueError('Client position mismatch')
                row.update(status='awaiting_visual_review',receipt=receipt)
            except Exception as error:row['error']=str(error)
            result['results'].append(row);save()
            print(name,row['status'],row.get('error',''),flush=True)
            if row['status']=='failed':raise ValueError(row['error'])

        # Create/mount inside the seam first; only then move the actual boat across it.
        made_fixture=True;creative=True
        result['currentCase']='boat_player_and_pig';save()
        command(['gamemode creative @p',f'tp @p {float(half-16)} 65.0 2048.0 -90 0',
                 f'summon minecraft:oak_boat {float(half-16)} 63.0 2048.0 {{Tags:["{FIXTURE}","ocean_boat"]}}',
                 f'summon minecraft:pig {float(half-16)} 64.0 2048.0 {{Tags:["{FIXTURE}","ocean_passenger"],NoAI:1b,Invulnerable:1b,PersistenceRequired:1b}}',
                 'ride @e[tag=ocean_passenger,limit=1] mount @e[tag=ocean_boat,limit=1]',
                 'ride @p mount @e[tag=ocean_boat,limit=1]'],'boat-prepare')
        command([f'tp @e[tag=ocean_boat,limit=1] {float(half+8)} 63.0 2048.0 -90 0'],'boat-cross')
        actual=wait_position('@e[tag=ocean_boat,limit=1]',[-half+8,63,2048],tolerance=3)
        command(['execute as @p on vehicle if entity @s[tag=ocean_boat]',
                 'execute as @e[tag=ocean_passenger,limit=1] on vehicle if entity @s[tag=ocean_boat]'],'boat-links')
        receipt=capture('boat')
        result['results'].append({'case':'boat_player_and_pig','actualBoat':actual,'receipt':receipt,
                                  'status':'awaiting_visual_review','mountLinks':'actual server assertions passed'})
        save()
    except Exception as error:
        result['runError']=str(error)
        raise
    finally:
        restore_errors=[]
        def restore(action):
            try:action()
            except Exception as error:restore_errors.append(str(error))
        if made_fixture:restore(lambda:command(['kill @e[tag='+FIXTURE+']'],'cleanup'))
        if creative:restore(lambda:command(['gamemode spectator @p'],'restore-mode'))
        try:
            if origin:
                # This suite expects the preview operator's spectator camera; restore its actual source pose/mode.
                actions=['camera '+' '.join(map(str,origin['position']+origin['lookAt']))]
                if shader_changed:actions.append('shaders '+('on' if origin['shaders'] else 'off'))
                actions.append('settle 2400')
                live.live_actions(actions,timeout=180)
        except Exception as error:restore_errors.append(str(error))
        if restore_errors:result['restoreError']='; '.join(restore_errors)
        save()
    return int(any(r['status']=='failed' for r in result['results']) or bool(result.get('restoreError')))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--world',help='Actual loaded save folder; required when more than one save exists')
    parser.add_argument('--shaders',choices=('current','on','off'),default='current')
    args=parser.parse_args()
    raise SystemExit(run(args.out,args.world,args.shaders))
