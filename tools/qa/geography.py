"""Versioned runtime-map oracle for audits beyond the authored Tamriel raster.

`py -3 tools/qa/geography.py refresh` evaluates Java's actual map without Minecraft.
The source/data receipt invalidates this cache when geography code or inputs change.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
TARGET=ROOT/'dist/world-tests/geography.json'
PATTERNS=['mod/src/main/java/com/nirncraft/tamriel/**/*.java',
          'mod/src/main/java/com/nirncraft/world/WorldConfig.java',
          'mod/src/main/resources/nirncraft/tamriel/**/*',
          'tools/qa/GeographyAudit.java','tools/qa/geography_audit.gradle','tools/qa/geography.py']


def inputs():
    h=hashlib.sha256()
    for p in sorted({p for pattern in PATTERNS for p in ROOT.glob(pattern) if p.is_file()}):
        h.update(p.relative_to(ROOT).as_posix().encode());h.update(hashlib.sha256(p.read_bytes()).digest())
    return h.hexdigest()


class Oracle:
    def __init__(self,data): self.data=data
    def coords(self,x,z):
        d=self.data;cx=int((x-d['minX'])//d['cell']);cz=int((z-d['minZ'])//d['cell'])
        return (cx,cz) if 0<=cx<d['width'] and 0<=cz<d['height'] else None
    def index_at(self,x,z):
        p=self.coords(x,z)
        return self.data['indices'][p[1]][p[0]] if p else None
    def elev_at(self,x,z):
        p=self.coords(x,z)
        return self.data['elevationCentimetres'][p[1]][p[0]]/100 if p else None
    def area(self,rid): return self.data['foreignLandCells'].get(rid,0)*(self.data['cell']/1000)**2


def load():
    receipt=TARGET.with_suffix('.receipt.json')
    if not TARGET.is_file() or not receipt.is_file(): return None
    proof=json.loads(receipt.read_text());raw=TARGET.read_bytes()
    if proof.get('inputHash')!=inputs() or proof.get('sha256')!=hashlib.sha256(raw).hexdigest(): return None
    return Oracle(json.loads(raw))


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['refresh','status']);args=p.parse_args()
    if args.action=='status':
        valid=load() is not None;print('Geography oracle: '+('current' if valid else 'missing/stale'));return int(not valid)
    before=inputs()
    result=subprocess.run(['powershell','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'tools/dev/build_fast.ps1'),
        '-Tasks','geographyAudit','-InitScript',str(ROOT/'tools/qa/geography_audit.gradle')],cwd=ROOT)
    if result.returncode:return result.returncode
    if inputs()!=before:raise RuntimeError('Geography inputs changed during survey; rerun')
    TARGET.with_suffix('.receipt.json').write_text(json.dumps({'inputHash':before,'sha256':hashlib.sha256(TARGET.read_bytes()).hexdigest()},indent=2)+'\n')
    print('Geography oracle verified');return 0


if __name__=='__main__':sys.exit(main())
