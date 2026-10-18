"""Expose the existing flow audit's actual FAIL/N/A metrics as a gate, not exit-zero green."""
import json
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]


def main():
    out=ROOT/'dist/world-tests/flow-audit.json'
    out.parent.mkdir(parents=True,exist_ok=True)
    result=subprocess.run([sys.executable,'-I',str(ROOT/'tools/qa/flow_audit.py'),'--json',str(out)],cwd=ROOT,
                          stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    if result.returncode:
        print(result.stdout.decode('utf-8',errors='replace')); return result.returncode
    data=json.loads(out.read_text())
    failures=[(rid,key,value[1]) for rid,row in data['regions'].items() for key,value in row['metrics'].items() if value[0]=='fail']
    unknown=sum(value[0]=='na' for row in data['regions'].values() for value in row['metrics'].values())
    for rid,key,value in failures: print('ERR',rid,key,value)
    print(f'{len(data["regions"])} land regions, {len(failures)} failing metrics, {unknown} unavailable metrics; {out}')
    return int(bool(failures))


if __name__=='__main__': sys.exit(main())
