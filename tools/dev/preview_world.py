"""Copy an owned stopped QA server world into the disposable client for real visual checks."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import live_world as live


def owned_source(path):
    path=path.resolve()
    default=(live.REPO/'mod/run/devworld/world').resolve()
    roots=[(live.REPO/'dist/world-tests/runtimes').resolve(),Path('E:/NirnCraft-scratch/world-benchmark').resolve()]
    if path!=default and not any(path.is_relative_to(root) and path.name=='world' for root in roots):
        raise ValueError('Source must be an owned devworld or storage-benchmark runtime world')
    if not (path/'level.dat').is_file():raise ValueError('Source is not a saved world')
    return path


def close_launcher():
    # Prism caches instance settings and can overwrite an external edit at launch.
    # Ask its windows to close normally; never kill it or any game process.
    script="""$items=@(Get-Process prismlauncher -ErrorAction SilentlyContinue)
foreach($launcherProcess in $items) {
 if(-not $launcherProcess.CloseMainWindow()) { throw 'Close Prism Launcher normally before editing the QA load setting' }
 if(-not $launcherProcess.WaitForExit(15000)) { throw 'Prism close is pending; no process was killed' }
}"""
    subprocess.run(['powershell','-NoProfile','-Command',script],check=True)


def select(name):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',name):raise ValueError('Simple world name required')
    if not (live.GAME/'saves'/name/'level.dat').is_file():raise ValueError('World absent from disposable instance')
    close_launcher()
    config=live.INSTANCE/'instance.cfg';text=config.read_text()
    updated,count=re.subn(r'-Dnirncraft\.autotest\.load=[^\s"]+', '-Dnirncraft.autotest.load='+name,text)
    if count!=1:raise ValueError('Expected one existing autotest load setting')
    backup=live.DEV/'config-backups'/(str(time.time_ns())+'.instance.cfg');backup.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(config,backup);live.atomic(config,updated.encode())
    import gzip,sys
    sys.path.insert(0,str(live.REPO/'tools/qa'))
    from mca import _nbt
    data=_nbt(gzip.decompress((live.GAME/'saves'/name/'level.dat').read_bytes()))
    seed=data['Data']['WorldGenSettings']['seed']
    live.atomic(live.DEV/'selected-world.json',json.dumps({'name':name,'seed':seed,'selected':time.time()}).encode())
    print('Selected disposable world:',name)


def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='action',required=True)
    imp=sub.add_parser('import');imp.add_argument('source',type=Path);imp.add_argument('name')
    choose=sub.add_parser('select');choose.add_argument('name')
    sub.add_parser('verify')
    args=parser.parse_args()
    if args.action=='verify':
        state=live.require_ready();live.live_actions(['identity'])
        expected=live.read_json(live.DEV/'selected-world.json')
        actual=live.read_json(live.GAME/'world-preview/camera-identity.json')
        log=(live.GAME/'logs/latest.log').read_text(errors='replace')
        if not expected or actual.get('seed')!=expected['seed'] or ('Autotest: loading saved world '+expected['name']) not in log:
            raise ValueError('Actual loaded save/seed does not match selected QA world; reject captures')
        proof={'world':expected['name'],'seed':actual['seed'],'pid':state['client']['pid'],'identity':actual,'verified':time.time()}
        live.atomic(live.DEV/'selected-world-verified.json',json.dumps(proof,indent=2).encode());print(json.dumps(proof,indent=2));return
    if live.processes():raise ValueError('Cleanly close the disposable client before copying or selecting worlds')
    if args.action=='select':select(args.name);return
    if not re.fullmatch(r'qa_[A-Za-z0-9_-]{1,60}',args.name):raise ValueError('Imported copy name must start qa_')
    source=owned_source(args.source)
    ready=source.parent/'outbox/ready'
    if ready.is_file():
        pid=int(ready.read_text().splitlines()[0])
        result=subprocess.run(['powershell','-NoProfile','-Command',f'if(Get-Process -Id {pid} -ErrorAction SilentlyContinue){{exit 1}}'],capture_output=True)
        if result.returncode:raise ValueError('Source server still running; stop it before copying')
    destination=live.GAME/'saves'/args.name
    if destination.exists():raise ValueError('Refusing to overwrite an existing QA world')
    shutil.copytree(source,destination,ignore=shutil.ignore_patterns('session.lock'))
    digest=hashlib.sha256();count=0
    for file in sorted(destination.rglob('*')):
        if file.is_file():
            digest.update(file.relative_to(destination).as_posix().encode());digest.update(hashlib.sha256(file.read_bytes()).digest());count+=1
    receipt={'source':str(source),'copy':str(destination),'files':count,'snapshotSha256':digest.hexdigest(),
             'note':'Copied while stopped. Core/generation identity and camera readiness must still be checked in Minecraft.'}
    (destination/'nirncraft-qa-import.json').write_text(json.dumps(receipt,indent=2)+'\n')
    select(args.name);print(json.dumps(receipt,indent=2))


if __name__=='__main__':main()
