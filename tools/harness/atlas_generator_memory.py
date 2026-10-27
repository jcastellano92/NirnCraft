"""Bounded native generator-input memory control, not Minecraft/renderer parity or a performance benchmark.

Run through agent_job with an exclusive 16 GiB bench window. The existing C++ tool consumes validated saved
generator inputs; its neutral material palette omits Minecraft textures, bricks and the 441-near-chunk workload.
No C ABI structs, launcher hooks, process-name kills or increased memory allowance are used here.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import time
import uuid

import atlas_memory_report as oracle
import process_memory

ROOT = Path(__file__).resolve().parents[2]
CAP_BYTES = 16 * 2**30
EXPECTED_TILES = 263
PART_NAMES = ('build', 'program', 'ingest', 'map', 'extra', 'stages', 'whole', 'stageCount')


def artifact_provenance(tool):
    paths = {'tool': Path(tool), **{name: ROOT / name for name in
             ('native/tools/gen_bake_tool.cpp', 'tools/harness/atlas_generator_memory.py',
              'tools/harness/test_atlas_generator_memory.py')}}
    for name in ('atlas_core.dll', 'libatlas_core.so', 'libatlas_core.dylib'):
        candidate = Path(tool).parent / name
        if candidate.is_file(): paths['adjacentCore:' + name] = candidate
    result = {}
    for name, path in paths.items():
        digest = hashlib.sha256()
        with path.open('rb') as stream:
            for block in iter(lambda: stream.read(1 << 20), b''): digest.update(block)
        result[name] = {'path': str(path.resolve()), 'bytes': path.stat().st_size, 'sha256': digest.hexdigest()}
    return result


def prepare_inputs(evidence, manifest=None):
    evidence = Path(evidence).resolve()
    dump = evidence / 'generator-dump'
    attempts = oracle.ingest_reports(dump, manifest)
    if len(attempts) != 1 or attempts[0].get('status') != 'VERIFIED':
        raise ValueError('one manifest-validated ingest attempt required; select --ingest relative to generator-dump')
    ingest = attempts[0]
    if not ingest['states'] or not ingest['biomes']: raise ValueError('generator control requires nonzero states and biomes')
    files = {}
    for name in ('prog.bin', 'map.bin', 'feat.bin', 'g2f.bin', 'f2g.bin'):
        path = dump / name
        size = path.stat().st_size
        if not size or size % 4: raise ValueError(name + ' must contain complete uint32 words')
        files[name] = {'path': str(path), 'bytes': size, 'fnv64': oracle.fnv_file(path),
                       'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    final = oracle.final_fingerprints(evidence)
    distinct = {tuple(item.get(k) for k in PART_NAMES) for item in final if item.get('status') == 'OBSERVED'}
    if len(distinct) != 1 or any(item.get('status') != 'OBSERVED' for item in final):
        raise ValueError('one distinct completed fingerprint from the saved live run is required')
    expected = dict(zip(PART_NAMES, next(iter(distinct))))
    if int(files['prog.bin']['fnv64'], 16) != int(expected['program'], 16) or int(files['map.bin']['fnv64'], 16) != int(expected['map'], 16):
        raise ValueError('saved program/map bytes disagree with the completed fingerprint')
    if int(ingest['fnv64'], 16) != int(expected['ingest'], 16) or not isinstance(ingest.get('programFnv64'), str) or int(ingest['programFnv64'], 16) != int(expected['program'], 16):
        raise ValueError('saved ingest/program identity disagrees with the completed fingerprint')
    maximum = 0
    with Path(ingest['binary']).open('rb') as stream:
        stream.seek(ingest['layout']['materials']['offset'] * 4)
        for block in iter(lambda: stream.read(65536), b''):
            maximum = max(maximum, max((word[0] for word in struct.iter_unpack('<I', block)), default=0))
    if maximum > 65535: raise ValueError('ingest material ID exceeds uint16 renderer domain')
    return {'evidence': str(evidence), 'dump': str(dump), 'files': files, 'ingest': ingest,
            'maxMaterialId': maximum, 'expectedFingerprint': expected,
            'scope': 'Saved generator binaries and real stage graph; EMPTY overlay block/street/road/tape payloads, neutral placeholder palette, no Minecraft/brick tables, 441-near workload or live overlay recording.'}


def tool_capabilities(tool, timeout):
    # An old tool receives argc==2 and exits usage before creating Vulkan; never probe it with workload inputs.
    result = subprocess.run([str(tool), '--capabilities'], capture_output=True, text=True, timeout=timeout,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    try: caps = oracle.DECODER.decode(result.stdout)
    except ValueError: caps = None
    if result.returncode != 0 or not isinstance(caps, dict) or not all(
            type(caps.get(k)) is int and caps[k] == 1 for k in ('generatorMemoryControl', 'explicitIngestValidation')):
        raise RuntimeError('built tool lacks the required pre-Vulkan capabilities')
    return caps


def require_policy(row):
    process_memory.require_accounting(row)
    if row['job'].get('limitBytes') != CAP_BYTES: raise RuntimeError('requires the existing 16 GiB named job cap')
    if process_memory.pressure(row): raise RuntimeError('job already reached its sampled 85% reserve')


def command(tool, inputs, destination, native_seconds):
    expected = inputs['expectedFingerprint']
    return [str(tool), inputs['dump'], str(destination / 'cache'), 'mode=bake', 'control=memory', 'feat=1',
            'first=6', 'last=3', 'lod2=0', 'expectTiles=263', 'x=4096.5', 'y=522', 'z=4096.5', 'seed=1234',
            'fp=' + str(int(expected['extra'], 16)), 'minx=-15360', 'minz=-5120', 'maxx=12800', 'maxz=16384', 'margin=0',
            'w=64', 'h=36', 'bg=1', 'bgcap=16.7', 'ingest=' + inputs['ingest']['binary'],
            'ingestWords=' + str(inputs['ingest']['words']), 'ingestFnv=' + inputs['ingest']['fnv64'],
            'controlSeconds=' + str(native_seconds), 'controlCancel=' + str(destination / 'cancel')]


def matched_parts(events, expected):
    records = [event for event in events if 'controlInputs' in event]
    if len(records) != 1: return False
    values = records[0].get('parts')
    if not isinstance(values, list) or len(values) != 8: return False
    try:
        return all(int(value, 16) == (expected[name] if name == 'stageCount' else int(expected[name], 16))
                   for name, value in zip(PART_NAMES, values))
    except (TypeError, ValueError):
        return False


def assess(events, snapshots, expected, returncode, stopped):
    completed = [e for e in events if e.get('phase') == 'levels']
    phase = completed[-1] if len(completed) == 1 else {}
    required = ('tiles', 'made', 'known', 'failed', 'durable', 'queued')
    complete = all(oracle.integer(phase.get(k)) for k in required) and phase['tiles'] == EXPECTED_TILES and \
        phase['made'] + phase['known'] == EXPECTED_TILES and phase['failed'] == 0 and phase['durable'] >= phase['made'] and phase['queued'] == 0
    cleanup = {name: any(s.get('phase') == name and s.get('observedWhileChildRunning') is True and
                         s.get('namedChildObserved') is True for s in snapshots)
               for name in ('after-feature-destroy', 'after-overlay-destroy', 'after-generator-destroy', 'after-context-destroy')}
    inputs_match = matched_parts(events, expected)
    ready = not stopped and returncode == 0 and complete and inputs_match and all(cleanup.values())
    return {'state': 'COMPLETED_CONTROL' if ready else 'UNVERIFIED', 'expectedTiles': EXPECTED_TILES,
            'actualTiles': phase.get('tiles'), 'madeTiles': phase.get('made'), 'knownTiles': phase.get('known'),
            'failedTiles': phase.get('failed'), 'durableTiles': phase.get('durable'), 'queuedWrites': phase.get('queued'),
            'fingerprintMatchesSavedRun': inputs_match, 'cleanupBoundarySamples': cleanup,
            'childRc': returncode, 'stopReason': stopped,
            'scope': 'Generator-input/stage-graph control with EMPTY overlay payloads and neutral palette; not full live reproduction, memory fix, visual parity or performance acceptance.'}


def stop_owned(proc, cancel, deadline, on_poll=None):
    """Only this Popen handle. Give the native loop a short cancel/drain interval, then terminate that same child."""
    cancel.touch(exist_ok=True)
    if on_poll is not None:
        grace = min(deadline - 3, time.monotonic() + 5)
        while proc.poll() is None and time.monotonic() < grace:
            try: on_poll()
            except (OSError, RuntimeError, ValueError): pass
            time.sleep(0.05)
        if proc.poll() is None: proc.kill()
    if proc.poll() is None:
        try: proc.wait(timeout=max(0.01, min(5.0, deadline - time.monotonic() - 3.0)))
        except subprocess.TimeoutExpired:
            proc.kill()
    if proc.poll() is None:
        try: proc.wait(timeout=max(0.01, min(3.0, deadline - time.monotonic())))
        except subprocess.TimeoutExpired: return False
    return proc.poll() is not None


def run(evidence, tool, *, manifest=None, seconds=180, sample=process_memory.sample):
    if not oracle.number(seconds) or not 30 <= seconds <= 180: raise ValueError('control bound must be 30..180 seconds')
    start = time.monotonic(); deadline = start + seconds
    inputs = prepare_inputs(evidence, manifest)
    tool = Path(tool).resolve()
    if not tool.is_file(): raise ValueError('built gen_bake_tool executable unavailable')
    for name in ('ATLAS_GEN_FP_NO_BUILD', 'ATLAS_SHADER_DIR'):
        if os.environ.get(name) not in (None, '', '0'): raise ValueError(name + ' must be off for saved-input control')
    initial = sample(); require_policy(initial)
    if deadline - time.monotonic() < 30: raise RuntimeError('preflight exhausted the work/cleanup budget; child not started')
    capabilities = tool_capabilities(tool, min(3, deadline - time.monotonic() - 25))
    provenance = artifact_provenance(tool)
    destination = Path(evidence) / ('generator-memory-control-' + time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8])
    destination.mkdir(); (destination / 'cache').mkdir()
    argv = command(tool, inputs, destination, max(1, int(deadline - time.monotonic() - 25)))
    (destination / 'inputs.json').write_text(json.dumps({**inputs, 'command': argv, 'toolCapabilities': capabilities,
                                                       'artifactProvenance': provenance}, indent=2) + '\n')
    env = dict(os.environ); env['ATLAS_MEMORY_DIAGNOSTICS'] = '1'
    proc, events, snapshots, stopped, phase = None, [], [], None, 'before-child'
    closed = False
    pending = b''; log_position = 0
    def record_memory(label, marker=None):
        row = sample([proc.pid] if proc else ()); row['phase'] = label
        row['childPid'] = proc.pid if proc else None
        row['observedWhileChildRunning'] = proc is not None and proc.poll() is None
        row['namedChildObserved'] = any(p.get('pid') == row['childPid'] and p.get('name') == tool.name and
                                        oracle.integer(p.get('handles')) and p['handles'] > 0 and not p.get('error')
                                        for p in row.get('processes', []))
        if marker:
            row['markerEpochMillis'] = marker.get('epochMillis')
            stamp = marker.get('epochMillis')
            row['markerGapSeconds'] = abs(row['epoch'] - stamp / 1000) if oracle.number(stamp) else None
            if not oracle.number(row['markerGapSeconds']) or row['markerGapSeconds'] > 1:
                row['namedChildObserved'] = False
        snapshots.append(row)
        with (destination / 'memory.jsonl').open('a') as stream: stream.write(json.dumps(row) + '\n')
        return row
    def read_events(reader, enforce=True):
        nonlocal pending, phase, log_position
        block = reader.read(1 << 20)
        log_position = reader.tell()
        pending += block
        parts = pending.split(b'\n'); pending = parts.pop()
        for raw in parts:
            try: event = oracle.DECODER.decode(raw.decode(errors='replace'))
            except ValueError: continue
            if not isinstance(event, dict): continue
            events.append(event)
            with (destination / 'events.jsonl').open('a') as stream: stream.write(json.dumps(event) + '\n')
            if isinstance(event.get('controlPhase'), str):
                phase = event['controlPhase']; row = record_memory(phase, event)
                if enforce:
                    process_memory.require_accounting(row)
                    if process_memory.pressure(row): raise RuntimeError('sampled job reached its 85% reserve')
            if enforce and 'controlInputs' in event and not matched_parts(events, inputs['expectedFingerprint']):
                raise RuntimeError('native input fingerprint differs from saved live run')
        return len(block)
    try:
        record_memory('before-child')
        if deadline - time.monotonic() <= 25: raise RuntimeError('budget expired before Popen')
        with (destination / 'tool.log').open('wb', buffering=0) as log:
            flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
            proc = subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT, env=env, creationflags=flags)
            with (destination / 'tool.log').open('rb') as reader:
                next_sample = 0.0
                while proc.poll() is None:
                    read_events(reader)
                    now = time.monotonic()
                    if now >= deadline - 15: raise RuntimeError('work deadline reached; cleanup reserve begins')
                    if now >= next_sample:
                        row = record_memory(phase); process_memory.require_accounting(row)
                        if process_memory.pressure(row): raise RuntimeError('sampled job reached its 85% reserve')
                        next_sample = now + 1
                    time.sleep(min(0.1, max(0.0, deadline - 15 - time.monotonic())))
                while read_events(reader, False): pass
            closed = proc.poll() is not None
    except (OSError, RuntimeError, ValueError) as error:
        stopped = type(error).__name__ + ': ' + str(error)
    finally:
        if proc and proc.poll() is None:
            with (destination / 'tool.log').open('rb') as reader:
                reader.seek(log_position)
                closed = stop_owned(proc, destination / 'cancel', deadline, lambda: read_events(reader, False))
                while read_events(reader, False): pass
        try: record_memory('after-child-cleanup')
        except (OSError, RuntimeError, ValueError): pass
    try: final_provenance = artifact_provenance(tool)
    except OSError: final_provenance = None
    if final_provenance != provenance:
        stopped = (stopped + '; ' if stopped else '') + 'source/executable provenance changed or became unavailable'
    result = assess(events, snapshots, inputs['expectedFingerprint'], proc.returncode if proc else None, stopped)
    result.update(output=str(destination), elapsedSeconds=time.monotonic() - start, childClosed=closed,
                  memoryCapBytes=CAP_BYTES, cleanupReserveSeconds=15,
                  artifactProvenance=provenance, finalArtifactProvenance=final_provenance,
                  artifactProvenanceUnchanged=final_provenance == provenance,
                  caveats=['Samples and marker timestamps are sequential, not atomic.',
                           'Adjacent core-library hashes identify files, not an independent loaded-module observation.',
                           'Unknown/exiting post-cleanup PID counters are not proof a child remains live.',
                           'GPU allocator/heap estimates are not CPU-private bytes and are not summed with them.'])
    (destination / 'receipt.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2), flush=True)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path)
    parser.add_argument('--tool', type=Path, default=ROOT / 'native/build/Release/gen_bake_tool.exe')
    parser.add_argument('--ingest', help='one ingest.json path relative to generator-dump')
    parser.add_argument('--seconds', type=float, default=180)
    args = parser.parse_args(argv)
    try: result = run(args.evidence, args.tool, manifest=args.ingest, seconds=args.seconds)
    except (OSError, RuntimeError, ValueError) as error: parser.exit(2, 'UNVERIFIED (no control accepted): ' + str(error) + '\n')
    return 0 if result['state'] == 'COMPLETED_CONTROL' else 2


if __name__ == '__main__': raise SystemExit(main())
