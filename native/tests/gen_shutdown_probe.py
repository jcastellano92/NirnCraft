"""One owned, <=60-second native child; captured validation/leak evidence, never a live-client launch.

Run only through the granted exclusive agent_job window. A red diagnostic is retained as FAILED/UNVERIFIED,
not converted into a passing regression. The production core is not modified by this fixture.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import struct
import sys
import time
import threading

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools' / 'harness'))
sys.path.insert(0, str(ROOT / 'tools' / 'dev'))
import atlas_generator_memory
import native_sources
import process_memory

CASES = tuple(owner + '-' + component for owner in ('owned', 'borrowed') for component in ('near', 'far', 'both'))
CLOSE_MODES = ('normal', 'producer', 'consumer', 'pre-producer', 'pre-consumer', 'pre-context-lock',
               'pre-scene-lock', 'pre-queue-lock', 'pre-admitted-work', 'pre-update')
CACHE_LIMIT = 64 * 2**20


def cache_file(path):
    path = Path(path).resolve(strict=True)
    size = path.stat().st_size
    if not path.is_file() or not 32 <= size <= CACHE_LIMIT:
        raise ValueError('pipeline cache must be a regular32-byte..64MiB file: ' + str(path))
    with path.open('rb') as stream: data = stream.read(CACHE_LIMIT + 1)
    if len(data) != size or len(data) > CACHE_LIMIT: raise ValueError('pipeline cache changed while reading')
    length, version, vendor, device, uuid = struct.unpack('<IIII16s', data[:32])
    if length != 32 or version != 1 or not vendor or not device or uuid == bytes(16):
        raise ValueError('unsupported/invalid pipeline cache header')
    return {'path': str(path), 'bytes': size, 'sha256': hashlib.sha256(data).hexdigest(),
            'header': {'length': length, 'version': version, 'vendor': vendor, 'device': device, 'uuid': uuid.hex()}}


def pipeline_cache_inputs(values):
    if not values: return None
    if len(values) != 1: raise ValueError('exactly one explicit pipeline cache base required; ambiguous selection refused')
    base = Path(values[0]).resolve(strict=True)
    if not base.is_file(): raise ValueError('explicit pipeline cache FILE required; directory discovery refused')
    records = [cache_file(base), cache_file(str(base) + '.gen')]
    if records[0]['header'] != records[1]['header']: raise ValueError('renderer/generator cache headers differ')
    if records[0]['path'] == records[1]['path']: raise ValueError('renderer/generator cache aliases refused')
    return {'originals': records}


def clone_pipeline_cache(inputs, destination):
    destination.mkdir(exist_ok=False)
    clones = []
    for record, name in zip(inputs['originals'], ('pipelines.bin', 'pipelines.bin.gen')):
        if cache_file(record['path']) != record: raise ValueError('original pipeline cache changed before copy')
        with Path(record['path']).open('rb') as stream: data = stream.read(CACHE_LIMIT + 1)
        if len(data) != record['bytes'] or hashlib.sha256(data).hexdigest() != record['sha256']:
            raise ValueError('original changed during copy')
        path = destination / name
        with path.open('xb') as stream: stream.write(data)
        cloned = cache_file(path)
        if cloned['sha256'] != record['sha256']: raise ValueError('pipeline cache clone differs')
        clones.append(cloned)
    return {**inputs, 'clonesBefore': clones, 'base': clones[0]['path']}


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''): h.update(block)
    return h.hexdigest()


def provenance(exe):
    core = next((exe.parent / name for name in ('atlas_core.dll', 'libatlas_core.so')
                 if (exe.parent / name).is_file()), None)
    stamp = exe.parent / 'atlas_core.sources'
    source = native_sources.source_hash(ROOT)
    if core is None or not stamp.is_file() or stamp.read_text().strip() != source:
        raise ValueError('adjacent core/source stamp is missing or differs from the unchanged audit sources')
    paths = [exe, core, stamp, Path(__file__), ROOT / 'native/tests/vk_backend_test.cpp',
             ROOT / 'native/tests/gen_shutdown_fixture.hpp', ROOT / 'native/tests/test_gen_shutdown_probe.py',
             ROOT / 'native/CMakeLists.txt']
    return {'nativeSourceHash': source, 'artifacts': [
        {'path': str(path.resolve()), 'bytes': path.stat().st_size, 'sha256': sha(path)} for path in paths],
        'limitation': 'Source/stamp/adjacent-file provenance; not loaded-module attestation.'}


def assess(text, case, returncode, stopped, unchanged, cache_used=False, close_mode=None):
    required = ['input-preflight-complete before-Vulkan', 'generator-destroy-begin', 'generator-destroy-end',
                'no-more-frames', 'context-destroy-begin', 'context-destroy-end']
    required += ['actual-copy-complete', 'token-released', 'external-device-destroy-begin',
                 'external-device-destroy-end', 'validation (the test\'s device, atlas on it): 0 errors'] \
        if case.startswith('borrowed-') else ['actual-owned-readback-complete', '[vulkan] validation on']
    if not case.endswith('-far'): required.append('near-witness status=0 flags=0')
    if not case.endswith('-near'): required.append('far-witness status=0')
    producer = close_mode in ('producer', 'pre-producer')
    pre_attach = close_mode is not None and close_mode.startswith('pre-')
    if close_mode is not None and case.startswith('borrowed-'):
        required += ['actual-queue-allocation', 'aliases-rejected']
    if pre_attach:
        required = ['input-preflight-complete before-Vulkan', 'actual-queue-allocation', 'aliases-rejected']
    if producer:
        if not pre_attach:
            required = [item for item in required if item not in ('context-destroy-begin', 'context-destroy-end',
                        'token-released', 'external-device-destroy-begin', 'external-device-destroy-end',
                        "validation (the test's device, atlas on it): 0 errors")]
        required += ['blocked status=-4', 'token-null; admission-denied; legacy-owner-retained',
                     'EXPECTED_BLOCKED producer; host-device-destroy-not-reached',
                     'blocked validation=0; resources-not-released; host-device-close-blocked',
                     'retained after close preparation failed: injected producer close refusal; generator ownership retained']
        if pre_attach: required.append('pre-attach real producer destroy')
    elif close_mode is not None:
        required += ['DRAINED preAttach=' + ('1' if pre_attach else '0'), 'post-close-admission-denied',
                     'native queue tail completed; frame and upload admission sealed']
        if pre_attach:
            required += ['pre-attach context-destroy-end', 'pre-attach external-device-destroy-end validation=0']
        if close_mode in ('consumer', 'pre-consumer'):
            required += ['blocked status=-8', 'token-null; admission-denied; legacy-owner-retained',
                         'consumer retry retains the same real queue-tail fence']
        lock = {'pre-context-lock': 1, 'pre-scene-lock': 2, 'pre-queue-lock': 3, 'pre-admitted-work': 4}.get(close_mode)
        if lock:
            match = re.search(r'bounded real-lock=' + str(lock) + r' elapsed=([0-9.]+)', text)
            if not match or not 4.5 <= float(match[1]) <= 5.75: required.append('UNVERIFIED bounded real lock timing')
        if close_mode == 'pre-update':
            required += ['admitted-update paused in taken stageScene branch', 'admitted-update joined status=0']
            primed = re.search(r'admitted-update primed lodCount=(\d+) regions=(\d+) revision=(\d+) actual-upload-complete', text)
            staged = re.search(r'admitted-update staged lodCount=(\d+) previous=(\d+) selected=(\d+) reached=(\d+) sealed=1 uploaded=1', text)
            if not primed or not int(primed[1]) or not int(primed[2]) or not staged or int(primed[3]) != int(staged[2]):
                required.append('UNVERIFIED nonempty prior actual staged upload')
            if not staged or not int(staged[1]) or int(staged[3]) <= int(staged[2]) or staged[3] != staged[4]:
                required.append('UNVERIFIED actual admitted update new revision reached')
            elif int(staged[3]) not in [int(value) for value in re.findall(r'admitted scene-upload submitted revision=(\d+)(?:\s|$)', text)]:
                required.append('UNVERIFIED actual scene upload submission')
            wait = re.search(r'admitted-update timeout status=-8 elapsed=([0-9.]+)', text)
            if not wait or not 4.5 <= float(wait[1]) <= 5.75: required.append('UNVERIFIED actual admitted update bounded timeout')
    if cache_used: required.append('pipeline-cache-device-match')
    missing = [marker for marker in required if marker not in text]
    retention = []
    if producer:
        retention += ['incomplete cleanup: refusing destroy with live/failed generator owners',
                      'retained after close preparation failed: injected producer close refusal; generator ownership retained']
    elif close_mode and close_mode != 'normal': retention.append('incomplete cleanup: refusing destroy of SEALED, undrained context')
    leaks = [line for line in text.splitlines() if not any(known in line for known in retention) and any(token in line for token in
             ('buffers stay', 'device is abandoned', 'splice objects are left', 'incomplete cleanup:', 'VUID-', 'Validation Error'))]
    if producer and any(marker in text for marker in ('external-device-destroy-begin', 'context-destroy-begin', 'token-released')):
        leaks.append('FAILED: host/context destruction or token release proceeded after failed producer close')
    unavailable = any(token in text for token in ('SKIP', 'validation requested but', 'validation layer is not installed'))
    complete = returncode == 0 and not stopped and unchanged and not missing and not leaks and not unavailable and 'FAIL' not in text
    conclusive_failure = not stopped and (bool(leaks) or (returncode not in (None, 0) and 'FAIL' in text))
    return {'state': 'FAILED' if conclusive_failure else ('EXPECTED_CLOSE_BLOCKED' if producer else 'CLEAN_SHUTDOWN_OBSERVED') if complete else
                    'UNVERIFIED' if stopped or missing or unavailable or not unchanged else 'FAILED',
            'case': case, 'terminalCloseMode': close_mode, 'childRc': returncode, 'stopReason': stopped, 'provenanceUnchanged': unchanged,
            'missingMarkers': missing, 'leakOrValidationEvidence': leaks[:100],
            'scope': 'Small populated saved-input lifecycle diagnostic; no proof of Minecraft memory-growth cause, full world parity, or performance.'}


def run(args):
    close_mode = getattr(args, 'terminal_close', 'normal')
    if close_mode != 'normal' and not args.case.startswith('borrowed-'):
        raise ValueError('checked terminal failure fixture requires a borrowed-device case')
    exe = Path(args.exe).resolve()
    if not exe.is_file() or sha(exe).lower() != args.exe_sha256.lower():
        raise ValueError('expected built test executable SHA256 required; no unsupported/old test binary is probed')
    inputs = atlas_generator_memory.prepare_inputs(args.evidence, args.ingest)
    caches = pipeline_cache_inputs(args.pipeline_cache)
    if caches and not args.case.startswith('borrowed-'):
        raise ValueError('cache-clone diagnostic currently requires borrowed device for actual UUID check before context creation')
    initial = provenance(exe)
    initial_ingest_sha = sha(inputs['ingest']['binary'])
    # This uses the existing validator, including original manifest layout/length/FNV and saved final program/ingest IDs.
    destination = Path(args.output).resolve()
    destination.mkdir(parents=True, exist_ok=False)
    if caches: caches = clone_pipeline_cache(caches, destination / 'pipeline-cache')
    (destination / 'inputs.json').write_text(json.dumps({'inputs': inputs, 'provenance': initial, 'ingestSha256': initial_ingest_sha,
                                                       'pipelineCaches': caches}, indent=2) + '\n')
    env = dict(os.environ)
    env.update(ATLAS_VK_VALIDATION='1', VK_KHRONOS_VALIDATION_VALIDATE_SYNC='true',
               ATLAS_VK_TEST_GEN_CASE=args.case, ATLAS_VK_TEST_GEN_INGEST=inputs['ingest']['binary'],
               ATLAS_VK_TEST_GEN_EXPECT_PROGRAM=inputs['files']['prog.bin']['fnv64'],
               ATLAS_VK_TEST_GEN_EXPECT_MAP=inputs['files']['map.bin']['fnv64'],
               ATLAS_VK_TEST_GEN_EXPECT_INGEST=inputs['ingest']['fnv64'])
    env.pop('ATLAS_VK_TEST_TERMINAL_CLOSE', None)
    env.pop('ATLAS_VK_TEST_GEN_FALLBACK', None)
    if close_mode != 'normal': env['ATLAS_VK_TEST_TERMINAL_CLOSE'] = close_mode
    if getattr(args, 'queue_fallback', False): env['ATLAS_VK_TEST_GEN_FALLBACK'] = '1'
    if caches:
        header = caches['originals'][0]['header']
        env.update(ATLAS_PIPELINE_CACHE=caches['base'], ATLAS_VK_TEST_GEN_CACHE_UUID=header['uuid'],
                   ATLAS_VK_TEST_GEN_CACHE_VENDOR=f"{header['vendor']:08x}", ATLAS_VK_TEST_GEN_CACHE_DEVICE=f"{header['device']:08x}")
    # The native child additionally clears transport/variant overrides just like the existing parity test.
    for key in ('ATLAS_GEN_FP_NO_BUILD', 'ATLAS_SHADER_DIR', 'ATLAS_GEN_CPU'):
        if env.get(key) not in (None, '', '0'): raise ValueError(key + ' must be off')
    argv = [str(exe), inputs['dump'], '--gen-shutdown-child']
    stopped = None; proc = None; closed = False; timer = None; expired = threading.Event()
    start = time.monotonic()
    with (destination / 'child.log').open('wb', buffering=0) as log:
        try:
            atlas_generator_memory.require_policy(process_memory.sample())
            proc = subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT, env=env,
                                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            deadline = time.monotonic() + 60
            def expire():
                if proc.poll() is None:
                    expired.set()
                    try: proc.kill()
                    except OSError: pass
            timer = threading.Timer(60, expire)
            timer.daemon = True
            timer.start()
            while proc.poll() is None:
                if time.monotonic() >= deadline: raise TimeoutError('60-second owned native child bound')
                row = process_memory.sample([proc.pid])
                row['phase'] = 'owned-native-child'
                with (destination / 'memory.jsonl').open('a') as stream: stream.write(json.dumps(row) + '\n')
                process_memory.require_accounting(row)
                if process_memory.pressure(row): raise RuntimeError('existing 85% memory reserve reached')
                if (destination / 'child.log').stat().st_size > 64 * 2**20: raise RuntimeError('native log exceeded64MiB')
                time.sleep(min(0.25, max(0, deadline - time.monotonic())))
        except (OSError, RuntimeError, TimeoutError, ValueError) as error:
            stopped = str(error)
        finally:
            if timer is not None: timer.cancel(); timer.join()
            if expired.is_set(): stopped = "60-second owned native child bound"
            # This executable creates no subprocesses: only its owned native threads are terminated on timeout.
            if proc is not None and proc.poll() is None: proc.kill()
            if proc is not None:
                try: proc.wait(timeout=5); closed = True
                except subprocess.TimeoutExpired: stopped = (stopped or '') + '; child did not reap'
    try: final = provenance(exe)
    except (OSError, ValueError): final = None
    text = (destination / 'child.log').read_text(encoding='utf-8', errors='replace')
    input_files = [inputs['files'][name] for name in ('prog.bin', 'map.bin')]
    try:
        input_unchanged = all(sha(row['path']) == row['sha256'] for row in input_files) and \
            sha(inputs['ingest']['binary']) == initial_ingest_sha
    except (OSError, KeyError): input_unchanged = False
    original_cache_unchanged = True
    if caches:
        try:
            original_cache_unchanged = [cache_file(row['path']) for row in caches['originals']] == caches['originals']
        except (OSError, ValueError):
            original_cache_unchanged = None
        try:
            caches['clonesAfter'] = [cache_file(row['path']) for row in caches['clonesBefore']]
            caches['clonePostStatus'] = 'OBSERVED'
        except (OSError, ValueError):
            caches['clonePostStatus'] = 'UNVERIFIED: clone post-read unavailable/out of bound'
            stopped = (stopped + '; ' if stopped else '') + caches['clonePostStatus']
        caches['originalsUnchanged'] = original_cache_unchanged
        caches['scope'] = 'Copied driver caches only; clone writes are expected and recorded separately from source/core provenance. No performance claim.'
    result = assess(text, args.case, proc.returncode if proc else None, stopped,
                    initial == final and input_unchanged and original_cache_unchanged, bool(caches),
                    close_mode if args.case.startswith('borrowed-') else None)
    result.update(childClosed=closed, elapsedSeconds=time.monotonic() - start, command=argv,
                  output=str(destination), initialProvenance=initial, finalProvenance=final, pipelineCaches=caches)
    (destination / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(text)
    print(json.dumps(result, indent=2))
    return 0 if result['state'] in ('CLEAN_SHUTDOWN_OBSERVED', 'EXPECTED_CLOSE_BLOCKED') and closed else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exe', required=True)
    parser.add_argument('--exe-sha256', required=True)
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--ingest', required=True, help='Explicit original manifest path relative to generator-dump')
    parser.add_argument('--case', choices=CASES, required=True)
    parser.add_argument('--terminal-close', choices=CLOSE_MODES, default='normal')
    parser.add_argument('--queue-fallback', action='store_true', help='Exercise Gen sharing the dedicated Atlas frame queue under its real mutex')
    parser.add_argument('--output', required=True, help='Fresh owned output directory; never overwritten')
    parser.add_argument('--pipeline-cache', action='append', help='Exactly one explicit existing renderer cache base; .gen companion required, originals read-only')
    args = parser.parse_args()
    try: return run(args)
    except (OSError, ValueError, RuntimeError) as error:
        print('UNVERIFIED:', error); return 2


if __name__ == '__main__':
    raise SystemExit(main())
