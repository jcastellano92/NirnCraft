"""Prepare a separate native core-only diagnostic instance; never launch Minecraft.

This does not modify or validate the full-pack instance and is not full-pack acceptance.
The selected Prism root retains its existing account; no credentials are copied.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import sys
import tempfile
import time

from prepare_prism_263 import configure, digest, exact_processes, extract, java_25, loopback_server

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/dev'))
from live_world import DIAGNOSTIC_NAME, TARGET, validate_diagnostic_instance


def prepare(archive: Path, prism_root: Path, java: Path | None, server: str = '') -> dict:
    server = loopback_server(server) if server else ''
    archive, prism_root = archive.resolve(strict=True), prism_root.resolve()
    java = java_25(java)
    for name in (DIAGNOSTIC_NAME, TARGET['instanceName']):
        if exact_processes(prism_root, name):
            raise ValueError('A disposable native client is running; close it cleanly before preparing diagnostics')
    prism_root.mkdir(parents=True, exist_ok=True)
    instances = prism_root / 'instances'
    instances.mkdir(exist_ok=True)
    destination = instances / DIAGNOSTIC_NAME
    with tempfile.TemporaryDirectory(prefix='skycraft-263-core-staging-', dir=prism_root) as temporary:
        source = extract(archive, Path(temporary))
        validate_diagnostic_instance(source)
        configure(source, java, live=True, instance_name=DIAGNOSTIC_NAME,
                  world='' if server else 'qa_core_diagnostic_263', server=server)
        receipt = {'diagnosticOnly': True, 'fullPackAcceptance': False, 'instanceName': DIAGNOSTIC_NAME,
                   'archive': str(archive), 'archiveSha256': digest(archive),
                   'coreSha256': digest(source / '.minecraft/mods/skycraft-core.jar'),
                   'java': str(java), 'preparedAt': time.time(), 'runtimeVerified': False,
                   'server': server or None, 'expectedTransport': 'tcp' if server else 'memory',
                   'activeFullPackChanged': False, 'launched': False}
        (source / '.minecraft/migration/diagnostic-preparation.json').write_text(json.dumps(receipt, indent=2) + '\n')
        backup = None
        if destination.exists():
            backups = prism_root / 'skycraft-263-core-diagnostic-backups'
            backups.mkdir(exist_ok=True)
            backup = backups / str(time.time_ns())
            destination.rename(backup)
        try:
            source.rename(destination)
        except Exception:
            if backup is not None:
                backup.rename(destination)
            raise
    return {'instance': str(destination), 'backup': str(backup) if backup else None, **receipt}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--prism-root', type=Path,
                        default=Path(os.environ.get('APPDATA', '')) / 'PrismLauncher')
    parser.add_argument('--java', type=Path)
    parser.add_argument('--server', type=loopback_server, default=None, metavar='127.0.0.1:PORT',
                        help='Connect to the dedicated loopback QA server instead of creating an integrated world')
    args = parser.parse_args()
    print(json.dumps(prepare(args.archive, args.prism_root, args.java, args.server), indent=2))


if __name__ == '__main__':
    main()
