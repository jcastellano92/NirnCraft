"""Exercise real realm shader routes at RealmLayout landmarks in the disposable Minecraft preview.

Images require visual review. A clear, ready capture and successful shader compilation do not prove
realm gameplay, complete LOD meshes, local lighting quality or performance. No saved chunks are regenerated.
"""
import argparse
import contextlib
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import time

ROOT=Path(__file__).resolve().parents[2]
SHADER_ERRORS=re.compile(r'ShaderCompileException|Failed to compile|Invalid program|Error compiling|Error linking'
                         r'|^.*\berror:\s|^.*\berror\s+C\d+',re.I|re.M)
CASES={
    'shivering_isles':('nirncraft:shivering_isles',0,0,'RealmLayout.SI_SHEOTH_R; New Sheoth'),
    'apocrypha':('nirncraft:apocrypha',0,0,'RealmLayout.AP_SPACING; chapter zero / Summit'),
    'soul_cairn':('nirncraft:soul_cairn',0,-180,'RealmLayout.SC_KEEP_Z; Valerica keep'),
    'coldharbour':('nirncraft:coldharbour',0,-200,'RealmLayout.CH_CITY_Z; Hollow City'),
    'sovngarde':('minecraft:the_end',0,-275,'RealmLayout.SG_HALL_Z; Hall of Valor'),
    'deadlands':('minecraft:the_nether',8,8,'RealmLayout.keepX/keepZ(0); Great Sigil Tower'),
}


def retain_driver_log(log_path, offset, output):
    """Keep driver diagnostics even when applying the requested shader mode throws."""
    with log_path.open('rb') as stream:
        # A client restart/log rotation must never make a truncated log look clean.
        if stream.seek(0,2) < offset:
            raise ValueError('Driver log rotated during realm capture')
        stream.seek(offset)
        emitted=stream.read().decode('utf8',errors='replace')
    output.write_text(emitted,encoding='utf8')
    return emitted


def return_home(live):
    # The operator's home command returns zero when already home. That is not a failed transition.
    live.live_actions(['identity'],timeout=30)
    identity=live.read_json(live.DEV/'camera-identity.json')
    if identity.get('dimension')=='minecraft:overworld':return
    if identity.get('dimension') not in {case[0] for case in CASES.values()}:
        raise ValueError('Refusing to route an unrecognized dimension through realm home')
    live.submit(['execute as @p at @p run nirncraft-realm home'],timeout=180)


def run(names,out):
    spec=importlib.util.spec_from_file_location('realm_live',ROOT/'tools/dev/live_world.py')
    live=importlib.util.module_from_spec(spec);spec.loader.exec_module(live)
    live.require_ready()
    out.mkdir(parents=True,exist_ok=True)
    if (out/'run.json').exists(): raise ValueError('Choose a new output directory; preserve existing observations')
    runtime=live.read_json(live.DEV/'outbox/runtime.json')
    shader=live.GAME/'shaderpacks/ComplementaryReimagined_r5.9.3.zip'
    data={'scope':__doc__,'runtime':runtime,'shaderSha256':hashlib.sha256(shader.read_bytes()).hexdigest(),
          'results':[],'visualAcceptance':'pending explicit image inspection; observations do not establish regional coverage'}
    def save():live.atomic(out/'run.json',(json.dumps(data,indent=2)+'\n').encode())
    save()
    try:
        for realm in names:
            dimension,x,z,plan=CASES[realm]
            result={'realm':realm,'dimension':dimension,'planReference':plan,'status':'failed'}
            started=time.monotonic()
            log_path=live.GAME/'logs/latest.log';offset=log_path.stat().st_size
            tag='realm-smoke-'+realm+'-'+str(time.time_ns())
            try:
                with (out/(realm+'.log')).open('w',encoding='utf8') as log,contextlib.redirect_stdout(log):
                    # Operator route exercises the actual entry path; a fixed landmark gives a repeatable scout candidate.
                    return_home(live)
                    live.submit(['execute as @p at @p run nirncraft-realm go '+realm,
                                 f'execute as @p at @p run nirncraft-realm look {x} {z} 90 120 35'],timeout=300)
                    live.live_actions(['ui close','shaders on','settle 1800','capture '+tag],timeout=300)
                captures=live.GAME/'build/autotest-screenshots'
                receipt=live.read_json(captures/(tag+'.json'))
                if receipt.get('dimension')!=dimension or receipt.get('shaders') is not True:
                    raise ValueError('Actual dimension or shader mode differs from requested case')
                if not receipt.get('cameraClear') or not receipt.get('readinessPassed') or receipt.get('nightVision') is not False:
                    raise ValueError('Capture lacks clearance/readiness/actual-lighting evidence')
                if receipt.get('coreSha256')!=runtime.get('coreSha256'):
                    raise ValueError('Capture core differs from the runtime under test')
                emitted=retain_driver_log(log_path,offset,out/(realm+'-driver.log'))
                if SHADER_ERRORS.search(emitted):
                    raise ValueError('Driver logged shader compilation/link errors')
                shutil.copy2(captures/(tag+'.png'),out/(realm+'.png'))
                shutil.copy2(captures/(tag+'.json'),out/(realm+'.json'))
                result.update(status='awaiting_visual_review',shaderRoute='runtime_exercised',receipt=receipt,
                              evidence=str(out/(realm+'.png')))
            except Exception as error:
                result['error']=str(error)
            finally:
                try:
                    emitted=retain_driver_log(log_path,offset,out/(realm+'-driver.log'))
                    if SHADER_ERRORS.search(emitted):
                        result['status']='failed'
                        result['driverError']='Driver logged shader compilation/link errors'
                except (OSError,ValueError) as error:
                    result['status']='failed'
                    result['driverError']=str(error)
            result['seconds']=round(time.monotonic()-started,3)
            data['results'].append(result);save()
            print(realm,result['status'],result.get('error',''),flush=True)
    finally:
        # Return through the normal path; never leave a pending command queue or overwrite the owner's instance.
        try:
            with (out/'return.log').open('w',encoding='utf8') as log,contextlib.redirect_stdout(log):
                return_home(live)
        except Exception as error:data['returnError']=str(error);save()
    return int(any(r['status']=='failed' for r in data['results']) or 'returnError' in data)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--realms',default='all',help='all or comma-separated realm IDs')
    parser.add_argument('--out',type=Path,default=ROOT/'dist/world-tests'/('realm-smoke-'+time.strftime('%Y%m%dT%H%M%SZ',time.gmtime())))
    args=parser.parse_args();names=list(CASES) if args.realms=='all' else args.realms.split(',')
    if not names or any(n not in CASES for n in names):parser.error('Unknown realm selection')
    return run(names,args.out)


if __name__=='__main__':raise SystemExit(main())
