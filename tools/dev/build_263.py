"""Run the exact26.3 build with isolated Java25, Gradle and output directories."""
from pathlib import Path
import argparse,hashlib,os,subprocess,sys
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from dev.process_guard import GuardBusy,process_guard
def checkout_output(root,scratch):
    # Preserve existing agent caches, and isolate worktrees regardless of their directory naming convention.
    if root.parent.name=='worktrees':
        return scratch/'wt'/root.name
    if (root/'.git').is_file():
        key=hashlib.sha256(os.path.normcase(str(root.resolve())).encode()).hexdigest()[:10]
        return scratch/'wt'/(root.name+'-'+key)
    return scratch

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--log',type=Path)
    p.add_argument('--offline',action='store_true',help='Use Gradle offline dependency resolution')
    p.add_argument('--run-dir',type=Path,help='Isolated dev runtime; never points at the preserved old instance')
    p.add_argument('--dev-dir',type=Path,help='Enable the dev command inbox in this separate directory')
    p.add_argument('tasks',nargs='*',default=['build'])
    a=p.parse_args()
    scratch=Path(os.environ.get('NIRNCRAFT_263_SCRATCH',r'E:\NirnCraft-scratch\migration-26.3')).resolve()
    location=Path(r'E:\NirnCraft-scratch\toolchains\java25-location.txt')
    java=Path(os.environ.get('NIRNCRAFT_JAVA25_HOME') or location.read_text(encoding='utf-8').strip())
    env=os.environ.copy();env['JAVA_HOME']=str(java);env['GRADLE_USER_HOME']=str(scratch/'gradle-home')
    env.pop('DEBUG',None) # Gradle's batch launcher otherwise echoes every command into diagnostic logs.
    # A git worktree (.claude/worktrees/<name>) builds into its own directory, so agents' builds never recompile over
    # each other or wait on one machine-wide lock; the Gradle home (dependency cache) stays shared. A new directory is
    # seeded with the shared one's Minecraft artifacts (moddev, minecraft-junit) instead of regenerating them.
    out=checkout_output(ROOT,scratch)
    if out!=scratch and not (out/'build'/'moddev').exists():
        import shutil
        for d in ('moddev','minecraft-junit'):
            if (scratch/'build'/d).exists():shutil.copytree(scratch/'build'/d,out/'build'/d,dirs_exist_ok=True)
    args=[str(ROOT/'mod/gradlew.bat'),*(a.tasks or ['build']),*(['--offline'] if a.offline else []),'--console=plain','-Dorg.gradle.jvmargs=-Xmx3G',
          '-PnirncraftBuildDir='+str(out/'build'),'--project-cache-dir',str(out/'project-cache')]
    if a.run_dir:args.append('-PnirncraftRunDir='+str(a.run_dir.resolve()))
    if a.dev_dir:args.append('-PnirncraftDevDir='+str(a.dev_dir.resolve()))
    try:
        out.mkdir(parents=True,exist_ok=True)
        with process_guard(out/'.native-build.lock','build_263.py'):
            if a.log:
                a.log.parent.mkdir(parents=True,exist_ok=True)
                with a.log.open('wb') as log:return subprocess.call(args,cwd=ROOT/'mod',env=env,stdout=log,stderr=subprocess.STDOUT)
            return subprocess.call(args,cwd=ROOT/'mod',env=env)
    except GuardBusy as exc:
        print('ERROR:',exc,file=sys.stderr)
        return 2
if __name__=='__main__':raise SystemExit(main())
