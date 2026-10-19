"""Paired saved-world regeneration comparison on owned F:/E: copies; never move the active world."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import time

ROOT=Path(__file__).resolve().parents[2]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,default=ROOT/'mod/run/devworld-26.3/world')
    parser.add_argument('--runs',type=int,default=2)
    args=parser.parse_args()
    if not 1<=args.runs<=4:raise ValueError('Use1..4 pairs')
    import live_world as live
    if live.processes():raise ValueError('Close the disposable preview before benchmarking a headless server')
    source=args.source.resolve()
    if not (source/'level.dat').is_file():raise ValueError('Saved source world required')
    # Source must be stopped. Refuse a live default server, including a boot in progress.
    ready=source.parent/'outbox/ready'
    if ready.is_file():
        pid=int(ready.read_text().splitlines()[0])
        alive=subprocess.run(['powershell','-NoProfile','-Command',f'if(Get-Process -Id {pid} -ErrorAction SilentlyContinue){{exit 1}}'],capture_output=True)
        if alive.returncode:raise ValueError('Source server still running')
    stamp=time.strftime('%Y%m%dT%H%M%SZ',time.gmtime())
    output=ROOT/'dist/weather-review/storage'/('world-'+stamp);output.mkdir(parents=True)
    # Freeze the original bytes once; every case begins with this identical snapshot.
    frozen=output/'source-world';shutil.copytree(source,frozen,ignore=shutil.ignore_patterns('session.lock'))
    digest=hashlib.sha256();size=0
    for file in sorted(frozen.rglob('*')):
        if file.is_file():
            raw=file.read_bytes();size+=len(raw);digest.update(file.relative_to(frozen).as_posix().encode());digest.update(hashlib.sha256(raw).digest())
    def inputs():
        digest=hashlib.sha256()
        for file in sorted((ROOT/'mod/src').rglob('*')):
            if file.is_file():digest.update(file.relative_to(ROOT).as_posix().encode());digest.update(hashlib.sha256(file.read_bytes()).digest())
        digest.update((ROOT/'mod/build.gradle').read_bytes())
        return digest.hexdigest()
    source_inputs=inputs()
    report={'sourceInputsSha256':source_inputs,'source':str(source),'snapshotSha256':digest.hexdigest(),'snapshotBytes':size,'cases':[],
            'limits':['Warm OS caches; same stopped world snapshot and sources, sequential servers, alternating order.',
                      'Boot/classpath stay on their existing drives. This isolates world/runtime placement, not a full installation move.',
                      'Road acceptance findings remain failures; generation completion is measured separately.']}
    dev=ROOT/'tools/dev/devserver.ps1';corridors=ROOT/'tools/qa/full_corridors.ps1'
    def ps(script,arguments,log):
        with log.open('wb') as stream:
            began=time.perf_counter();p=subprocess.run(['powershell','-NoProfile','-ExecutionPolicy','Bypass','-File',str(script),*arguments],cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT)
        return p.returncode,round(time.perf_counter()-began,3)
    for iteration in range(args.runs):
        for drive in (('F','E') if iteration%2==0 else ('E','F')):
            if inputs()!=source_inputs:raise ValueError('Sources changed during paired benchmark; preserve partial results and restart comparison')
            base=ROOT/'dist/world-tests/runtimes' if drive=='F' else Path('E:/NirnCraft-scratch/world-benchmark')
            runtime=(base/(stamp+'-'+drive+'-'+str(iteration))).resolve()
            # No recursive delete, reuse or move; every case has a newly owned path.
            if runtime.exists():raise ValueError('Refusing existing benchmark runtime')
            began=time.perf_counter();shutil.copytree(frozen,runtime/'world');copy_seconds=time.perf_counter()-began
            name=drive+'-'+str(iteration);case={'drive':drive,'iteration':iteration,'runtime':str(runtime),'copySeconds':round(copy_seconds,3)}
            try:
                code,seconds=ps(dev,['start','-RuntimeRoot',str(runtime)],output/(name+'-boot.log'))
                case.update(bootExit=code,bootSeconds=seconds)
                if code:raise RuntimeError('Benchmark server did not boot: '+name)
                code,seconds=ps(corridors,['-Reuse','-RuntimeRoot',str(runtime),'-Json',str(output/(name+'-roads.json'))],output/(name+'-corridors.log'))
                text=(output/(name+'-corridors.log')).read_text(errors='replace')
                case.update(corridorExit=code,corridorSeconds=seconds)
                # Keep the original full timing summary alongside parsed stage samples.
                case['regenSummaries']=list(dict.fromkeys(line.strip().removeprefix('[log] [dev] ') for line in text.splitlines() if 'Regen done:' in line))
                stages=re.findall(r'generated in ([0-9.]+) s, paved in ([0-9.]+) s, saved in ([0-9.]+) s, total ([0-9.]+) s',text)
                if stages:case['regenSeconds']=dict(zip(('generation','paving','saving','total'),map(float,stages[-1])))
                case['completedRegeneration']='regenerated and paved' in text
                if not case['completedRegeneration']:raise RuntimeError('Regeneration incomplete: '+name)
            finally:
                case['stopExit'],case['stopSeconds']=ps(dev,['stop','-RuntimeRoot',str(runtime)],output/(name+'-stop.log'))
                report['cases'].append(case);(output/'benchmark.json').write_text(json.dumps(report,indent=2)+'\n')
            print(name,json.dumps(case),flush=True)
    report['medianCorridorSeconds']={drive:statistics.median(c['corridorSeconds'] for c in report['cases'] if c['drive']==drive) for drive in ('F','E')}
    (output/'benchmark.json').write_text(json.dumps(report,indent=2)+'\n')
    print('BENCHMARK',output,report['medianCorridorSeconds'])


if __name__=='__main__':main()
