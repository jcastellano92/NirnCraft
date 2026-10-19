"""Run the actual full native server in a new QA copy, then save and stop it.

Coordinate the single-runtime gate before executing this tool. EULA acceptance
and offline mode apply only to the new disposable QA copy, never the package.
Only the exact Popen child started here can be stopped or terminated.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import queue
import re
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from build_server_instance import NATIVE_ARGS, SERVER_TARGET, check_install, java_25, server_mods
from build_prism_instance import file_hash
from validate_pack import validate_core_jar, validate_pack
from native_server_evidence_263 import command_failure, dimension_ids, shutdown_evidence


def extract(archive, directory):
    """Validate every path before any extraction; refuse traversal/symlinks/duplicates."""
    directory = Path(directory).resolve(strict=True)
    with zipfile.ZipFile(archive) as source:
        planned, seen = [], set()
        for entry in source.infolist():
            name = entry.filename.replace('\\', '/')
            relative = Path(name)
            if not name.startswith('server/') or relative.is_absolute() or '..' in relative.parts or ':' in name:
                raise ValueError(f'Unsafe server archive path: {entry.filename}')
            if stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError(f'Server archive contains a symbolic link: {entry.filename}')
            target = (directory / relative).resolve()
            if not target.is_relative_to(directory) or target in seen:
                raise ValueError(f'Duplicate or escaping archive path: {entry.filename}')
            seen.add(target)
            planned.append((entry, target))
        if sum(entry.file_size for entry, _ in planned) > 4 * 1024**3:
            raise ValueError('Unexpected server archive size')
        for entry, target in planned:
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(entry) as incoming, target.open('xb') as output:
                    shutil.copyfileobj(incoming, output)
                if target.name == 'run.sh':
                    target.chmod(0o755)
    return directory / 'server'


def validate_server(server):
    receipt = json.loads((server / 'migration/server-receipt.json').read_text(encoding='utf-8'))
    if receipt.get('target') != SERVER_TARGET or not receipt.get('fullServerPack'):
        raise ValueError('Not the exact native full server package')
    rows = server_mods(validate_pack())
    if {p.name for p in (server / 'mods').glob('*.jar')} != {r['filename'] for r in rows} | {'nirncraft-core.jar'}:
        raise ValueError('Unexpected or missing server mod')
    for row in rows:
        if file_hash(server / 'mods' / row['filename'], row['hashFormat']) != row['hash']:
            raise ValueError(f"Server mod differs from lock: {row['filename']}")
    core = server / 'mods/nirncraft-core.jar'
    validate_core_jar(core)
    if file_hash(core, 'sha256') != receipt['coreSha256']:
        raise ValueError('Server core differs from package receipt')
    check_install(server)
    return receipt


def run(archive, java, work_root, startup_timeout=900, command_timeout=180, heap='4G', commands=None, dev_control=False):
    archive = Path(archive).resolve(strict=True)
    java, environment = java_25(java)
    if not re.fullmatch(r'[1-9][0-9]*[MG]', heap):
        raise ValueError('Heap must be a positive amount such as 4G')
    work_root = Path(work_root).resolve()
    work_root.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='full-server-263-', dir=work_root))
    server = extract(archive, work)
    package = validate_server(server)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    with (server / 'server.properties').open('a', encoding='utf-8') as properties:
        properties.write('\n# Isolated automated QA only.\nonline-mode=false\nserver-ip=127.0.0.1\n'
                         f'server-port={port}\npause-when-empty-seconds=-1\nview-distance=4\nsimulation-distance=4\n')
    (server / 'eula.txt').write_text('# Isolated automated QA only.\neula=true\n', encoding='utf-8')
    (server / 'qa_jvm_args.txt').write_text('-Xms1G\n-Xmx' + heap + '\n-XX:+UseG1GC\n', encoding='utf-8')
    token = uuid.uuid4().hex
    args = [str(java), '@qa_jvm_args.txt', '-Dnirncraft.serverQa=' + token,
            '@' + (NATIVE_ARGS / ('win_args.txt' if os.name == 'nt' else 'unix_args.txt')).as_posix(), 'nogui']
    if dev_control:
        # Explicit QA only; preserve normal spawn ownership while enabling the
        # production jar's existing public diagnostic commands/inbox.
        args[2:2] = ['-Dnirncraft.dev=' + (server / 'qa-control').as_posix(), '-Dnirncraft.dev.keepSpawn=true']
    started = time.monotonic()
    result = {'schema': 1, 'target': SERVER_TARGET, 'archive': str(archive), 'archiveSha256': file_hash(archive, 'sha256'),
              'coreSha256': package['coreSha256'], 'runtime': str(server), 'java': str(java), 'port': port,
              'fullServerPack': True, 'runtimeVerified': False, 'multiplayerVerified': False, 'stages': [], 'failures': []}
    lines = queue.Queue()
    process = None
    collector = None

    def wait_for(pattern, timeout, stage):
        matcher, deadline = re.compile(pattern), time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f'{stage} did not complete within {timeout}s')
            try:
                line = lines.get(timeout=min(0.2, remaining))
            except queue.Empty:
                if process.poll() is not None:
                    raise RuntimeError(f'Server exited during {stage}: {process.returncode}')
                continue
            if matcher.search(line):
                result['stages'].append({'stage': stage, 'elapsedSeconds': round(time.monotonic() - started, 2), 'evidence': line.rstrip()})
                print(f'[{stage}] {line.rstrip()}', flush=True)
                return
            if command_failure(line):
                raise RuntimeError(f'{stage} failed: {line.rstrip()}')

    def send(command):
        if process.poll() is not None:
            raise RuntimeError('Owned server already exited')
        process.stdin.write(command + '\n')
        process.stdin.flush()

    with (work / 'console.log').open('w', encoding='utf-8', buffering=1) as console:
        try:
            process = subprocess.Popen(args, cwd=server, env=environment, stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                       encoding='utf-8', errors='replace', bufsize=1)
            result['pid'], result['processToken'] = process.pid, token
            (work / 'owned-process.json').write_text(json.dumps({'pid': process.pid, 'java': str(java),
                    'runtime': str(server), 'token': token, 'args': args}, indent=2), encoding='utf-8')

            def collect():
                for line in process.stdout:
                    console.write(line)
                    lines.put(line)

            collector = threading.Thread(target=collect, daemon=True)
            collector.start()
            print(json.dumps({'pid': process.pid, 'runtime': str(server), 'port': port}), flush=True)
            wait_for(r'Done \(', startup_timeout, 'startup')
            for command, expected in [('list', r'There are \d+ of a max of \d+ players online'),
                                      ('nirncraft-bake check', r'Bake is up to date[.]')]:
                send(command)
                wait_for(expected, command_timeout, command)
            for command in commands or []:
                send(command['command'])
                wait_for(command['expect'], command.get('timeout', command_timeout), command['command'])
            send('save-all flush')
            wait_for(r'Saved the game', command_timeout, 'save-all flush')
            send('stop')
            process.wait(timeout=90)
            collector.join(timeout=5)
            if process.returncode != 0:
                raise RuntimeError(f'Owned server exit code: {process.returncode}')
            text = (work / 'console.log').read_text(encoding='utf-8')
            result['shutdown'] = shutdown_evidence(text, dimension_ids(server / 'mods/nirncraft-core.jar'))
            errors = [line for line in text.splitlines() if re.search(r'/(?:ERROR|FATAL)\]|\[(?:ERROR|FATAL)\]|Exception in thread', line)]
            if errors or list((server / 'crash-reports').glob('*.txt')):
                result['failures'].extend(errors[:60])
                raise RuntimeError('Full server wrote errors/crash reports; inspect retained evidence')
            result['stages'].append({'stage': 'clean shutdown', 'elapsedSeconds': round(time.monotonic() - started, 2)})
            result['runtimeVerified'] = True
        except Exception as error:
            result['failures'].append(str(error))
        finally:
            if process is not None and process.poll() is None:
                try:
                    send('stop')
                    process.wait(timeout=45)
                except (OSError, subprocess.TimeoutExpired):
                    process.terminate()  # Exact owned child handle, after orderly stop failed.
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
                    result['failures'].append('Owned child needed forced termination after its stop deadline')
                if collector is not None:
                    collector.join(timeout=5)
            if process is not None:
                try:
                    process.stdin.close()
                    process.stdout.close()
                except OSError:
                    pass
    result['elapsedSeconds'] = round(time.monotonic() - started, 2)
    (work / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2), flush=True)
    return 0 if result['runtimeVerified'] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, default=ROOT / 'dist/NirnCraft-26.3-server.zip')
    parser.add_argument('--java', required=True, type=Path)
    parser.add_argument('--work-root', type=Path, default=ROOT / 'dist/server-test-263')
    parser.add_argument('--startup-timeout', type=int, default=900)
    parser.add_argument('--command-timeout', type=int, default=180)
    parser.add_argument('--heap', default='4G')
    parser.add_argument('--commands', type=Path, help='JSON array of command/expect/optional timeout objects')
    parser.add_argument('--dev-control', action='store_true', help='Enable NirnCraft development diagnostics in this isolated QA copy only')
    args = parser.parse_args()
    commands = json.loads(args.commands.read_text(encoding='utf-8')) if args.commands else None
    return run(args.archive, args.java, args.work_root, args.startup_timeout, args.command_timeout, args.heap, commands, args.dev_control)


if __name__ == '__main__':
    raise SystemExit(main())
