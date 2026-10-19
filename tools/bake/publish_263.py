"""Publish a complete native bundle only after its real server job verified it.

The staging tree and previous bundle are retained. No world directory is touched.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from native_server_evidence_263 import dimension_ids, shutdown_evidence
NAMES = ('landmass.bin', 'roadplan.bin', 'streetplan.bin', 'sceneplan.bin')


def validate(directory, expected):
    directory = Path(directory).resolve(strict=True)
    if not re.fullmatch('[0-9a-f]{64}', expected):
        raise ValueError('Expected the actual native runtime input hash')
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('format') != 1 or manifest.get('inputs') != expected or set(manifest.get('files', [])) != set(NAMES):
        raise ValueError('Native bake manifest is incomplete or has different runtime inputs')
    hashes = {}
    for name in NAMES:
        raw = (directory / name).read_bytes()
        length = int.from_bytes(raw[:2], 'big')
        if raw[2:2 + length].decode('ascii') != expected or not zlib.decompress(raw[2 + length:]):
            raise ValueError('Native bake is corrupt, empty or from another generation: ' + name)
        hashes[name] = hashlib.sha256(raw).hexdigest()
    if list(directory.glob('*.verify-mismatch.raw')):
        raise ValueError('Native independent-computation mismatch retained in staging')
    return hashes


def publish(staging, target, expected, backup, evidence, done):
    staging, target, backup = Path(staging).resolve(strict=True), Path(target).resolve(), Path(backup).resolve()
    evidence, done = Path(evidence), Path(done)
    output = evidence.read_text(encoding='utf-8-sig')
    if 'status=OK' not in done.read_text(encoding='utf-8-sig').splitlines():
        raise ValueError('Native bake job did not complete successfully')
    if not ('Bake is up to date.' in output or 'rebaked and verified into' in output) or 'Runtime bake inputs: ' + expected not in output:
        raise ValueError('Missing actual native bake verification and runtime input evidence')
    hashes = validate(staging, expected)
    if staging == target:
        return {'inputs': expected, 'changed': False, 'nativeVerified': True, 'files': hashes, 'evidence': str(evidence)}
    if backup.exists() or backup.is_relative_to(target) or staging.is_relative_to(target):
        raise ValueError('Backup/staging must preserve a separate, fresh bundle')
    target.mkdir(parents=True, exist_ok=True)
    backup.mkdir(parents=True)
    shutil.copytree(target, backup / 'previous', dirs_exist_ok=False)
    temporary = target.parent / ('baked-native-staging-' + uuid.uuid4().hex)
    temporary.mkdir()
    for name in (*NAMES, 'manifest.json'):
        shutil.copyfile(staging / name, temporary / name)
    validate(temporary, expected)
    replaced = []
    try:
        # Manifest is last; the server has already stopped before publication.
        for name in (*NAMES, 'manifest.json'):
            os.replace(temporary / name, target / name)
            replaced.append(name)
    except Exception:
        for name in replaced:
            previous = backup / 'previous' / name
            if previous.exists():
                shutil.copyfile(previous, target / name)
            else:
                (target / name).unlink()
        raise
    temporary.rmdir()
    result = {'inputs': expected, 'changed': True, 'nativeVerified': True, 'files': hashes,
              'previousBundle': str(backup / 'previous'), 'evidence': str(evidence)}
    (backup / 'publication.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--staging', type=Path, required=True)
    parser.add_argument('--expected', required=True)
    parser.add_argument('--backup', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--done', type=Path, required=True)
    parser.add_argument('--shutdown-log', type=Path, required=True)
    args = parser.parse_args()
    expected = dimension_ids(ROOT / 'mod/src/main/resources') | {'nirncraft:scratch'}
    shutdown = shutdown_evidence(args.shutdown_log.read_text(encoding='utf-8-sig'), expected)
    result = publish(args.staging, ROOT / 'mod/src/main/resources/nirncraft/baked', args.expected,
                     args.backup, args.evidence, args.done)
    print(json.dumps({**result, 'shutdown': shutdown}, indent=2))


if __name__ == '__main__':
    main()
