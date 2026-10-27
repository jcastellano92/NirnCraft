"""Offline allocation evidence, not a memory/performance pass. Missing observations stay UNVERIFIED.

Usage: py -3 tools/harness/atlas_memory_report.py EVIDENCE [--compare OTHER] [--utc-offset=-04:00]
Writes a fresh memory-report-* subdirectory without modifying input evidence. Exit 2 means incomplete evidence.
CPU private/working set, JVM/NIO, VMA allocations/reservations and raw Vulkan allocations are different views;
they are never summed or subtracted here. Profile/process observations are sequential, not atomic snapshots.
"""
from __future__ import annotations

import argparse
import bisect
import csv
from datetime import datetime, timedelta, timezone
import json
import math
from pathlib import Path
import re
import struct
import uuid

DECODER = json.JSONDecoder(parse_constant=lambda x: (_ for _ in ()).throw(ValueError('nonfinite JSON ' + x)))
MARKER = re.compile(r'(?<!\S)(core|mcMemory)=')
TOD = re.compile(r'^(\d\d:\d\d:\d\d)(?:\.(\d+))?')
FINGERPRINT = re.compile(r'build ([0-9a-fA-F]+) program ([0-9a-fA-F]+) ingest ([0-9a-fA-F]+) '
                         r'map ([0-9a-fA-F]+) extra ([0-9a-fA-F]+) stages ([0-9a-fA-F]+) '\
                         r'\((\d+)\) whole ([0-9a-fA-F]+)')
FNV_OFFSET, FNV_PRIME, MASK = 1469598103934665603, 1099511628211, (1 << 64) - 1
COMPONENTS = ('ids', 'flags', 'tints', 'materials')
PROFILE_FIELDS = ('line', 'epoch', 'timeSource', 'alignment', 'memoryEpoch', 'gapSeconds', 'phase',
                  'alignmentReason', 'vmaStatus', 'heapStatus', 'directStatus', 'mappedStatus', 'backendStatus', 'vmaAllocationBytes', 'vmaBlockBytes',
                  'vmaAllocationCount', 'vmaBlockCount', 'vmaQueryNs', 'javaUsedBytes', 'javaCommittedBytes',
                  'javaMaxBytes', 'directMemoryUsedBytes', 'directCapacityBytes', 'directCount',
                  'mappedMemoryUsedBytes', 'mappedCapacityBytes', 'mappedCount', 'backendAllocatedBytes',
                  'backendRetiredBytes', 'backendRetiredCount', 'backendSubmittedFrames', 'backendCompletedFrames')


def number(value):
    try:
        return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0
    except OverflowError:
        return False


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def allocation_counts(value):
    return isinstance(value, dict) and all(integer(value.get(k)) for k in
        ('allocationBytes', 'blockBytes', 'allocationCount', 'blockCount')) and value['allocationBytes'] <= value['blockBytes']


def unverified(reason):
    return {'status': 'UNVERIFIED', 'reason': reason}


def read_json(path):
    try:
        with path.open(encoding='utf-8-sig') as stream:
            value = json.load(stream, parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))
        return value if isinstance(value, dict) else unverified('JSON root is not an object')
    except (OSError, ValueError) as error:
        return unverified(type(error).__name__ + ': ' + str(path))


def fields(line):
    """Decode one complete JSON value at a time; marker-looking strings inside it are never rescanned."""
    result, errors, cursor = {}, {}, 0
    while match := MARKER.search(line, cursor):
        key = match[1]
        cursor = match.end()
        duplicate = key in result or key in errors
        while cursor < len(line) and line[cursor].isspace(): cursor += 1
        try:
            value, cursor = DECODER.raw_decode(line, cursor)
            if not isinstance(value, dict):
                raise ValueError('not an object')
            if duplicate:
                result.pop(key, None)
                errors[key] = 'duplicate field'
            else:
                result[key] = value
        except ValueError:
            errors[key] = 'malformed or non-object JSON'
    return result, errors


def observe(summary, key, value, epoch):
    if not number(value):
        return
    entry = summary.setdefault(key, {'count': 0, 'first': value, 'firstEpoch': epoch, 'max': value})
    entry.update(count=entry['count'] + 1, last=value, lastEpoch=epoch,
                 max=max(entry['max'], value), growth=value - entry['first'])


def memory_rows(root):
    rows, errors = [], []
    try:
        with (root / 'memory.jsonl').open(encoding='utf-8-sig') as stream:
            for line_number, line in enumerate(stream, 1):
                try:
                    row = DECODER.decode(line)
                    if not isinstance(row, dict) or not number(row.get('epoch')):
                        raise ValueError('missing epoch')
                    row['_line'] = line_number
                    rows.append(row)
                except ValueError:
                    if len(errors) < 20: errors.append({'line': line_number, 'reason': 'invalid memory JSON/epoch'})
    except OSError:
        errors.append({'reason': 'memory.jsonl unavailable'})
    rows.sort(key=lambda r: r['epoch'])
    return rows, errors


def process_status(process):
    if process.get('error'):
        return 'UNVERIFIED query error'
    if not integer(process.get('pid')) or not isinstance(process.get('name'), str) or process.get('name') == 'unknown' or not process.get('name') or process.get('handles') == 0:
        return 'UNVERIFIED unknown or exiting process'
    if not number(process.get('privateBytes')) or not number(process.get('workingSetBytes')):
        return 'UNVERIFIED missing counters'
    return 'OBSERVED named process; creation identity not recorded here'


def cpu_report(rows, destination):
    metrics, phases, pids, incomplete = {}, {}, set(), 0
    with destination.open('w', newline='', encoding='utf-8') as stream:
        keys = ['epoch', 'phase', 'pid', 'name', 'client', 'inJob', 'status', 'privateBytes', 'workingSetBytes', 'peakCommitBytes', 'handles']
        writer = csv.DictWriter(stream, fieldnames=keys); writer.writeheader()
        for row in rows:
            epoch, phase = row['epoch'], row.get('phase', 'unknown')
            phase_stats = phases.setdefault(phase, {})
            job = row.get('job') if isinstance(row.get('job'), dict) else {}
            observe(metrics, 'jobSampledPrivateBytes', job.get('sampledPrivateBytes'), epoch)
            observe(phase_stats, 'jobSampledPrivateBytes', job.get('sampledPrivateBytes'), epoch)
            observe(metrics, 'jobReportedPeakCommitBytes', job.get('peakCommitBytes'), epoch)
            observe(phase_stats, 'jobReportedPeakCommitBytes', job.get('peakCommitBytes'), epoch)
            for process in row.get('processes', []) if isinstance(row.get('processes'), list) else []:
                if not isinstance(process, dict): continue
                status = process_status(process)
                writer.writerow({**{k: process.get(k) for k in keys if k in process},
                                 'epoch': epoch, 'phase': phase, 'status': status})
                if not process.get('client'): continue
                if not status.startswith('OBSERVED') and phase != 'after-client-cleanup': incomplete += 1
                pid = process.get('pid'); pids.add(str(pid))
                for counter in ('privateBytes', 'workingSetBytes'):
                    # Unknown/exiting samples retain their raw values in CSV, not a false active-client endpoint.
                    if status.startswith('OBSERVED'):
                        observe(metrics, f'client.{pid}.{counter}', process.get(counter), epoch)
                        observe(phase_stats, f'client.{pid}.{counter}', process.get(counter), epoch)
    has_client = any(k.startswith('client.') for k in metrics)
    return {'status': 'OBSERVED' if has_client and not incomplete else 'UNVERIFIED', 'incompleteActiveClientSamples': incomplete,
            'observedNamedClientCounters': metrics, 'byPhase': phases, 'sampledClientPids': sorted(pids),
            'scope': 'Observed counter growth, not current liveness or verified process creation identity.'}


def nearest(rows, epochs, epoch):
    if not rows or not number(epoch): return None, None
    at = bisect.bisect_left(epochs, epoch)
    choices = [r for i in (at - 1, at) if 0 <= i < len(rows) for r in [rows[i]]]
    row = min(choices, key=lambda r: abs(r['epoch'] - epoch))
    return row, abs(row['epoch'] - epoch)


def profile_epoch(line, memory, rows, tz):
    stamp = memory.get('timestampMillis') if isinstance(memory, dict) else None
    if number(stamp): return stamp / 1000, 'mcMemory.timestampMillis'
    match = TOD.match(line)
    if not match or not rows: return None, 'UNVERIFIED missing timestamp'
    try:
        hour, minute, second = map(int, match[1].split(':'))
        micros = int((match[2] or '')[:6].ljust(6, '0'))
        # Date inference cannot distinguish an appended older session with the same local time.
        dates = {datetime.fromtimestamp(r['epoch'], tz).date() for r in rows}
        candidates = [datetime.combine(day + timedelta(days=delta), datetime.min.time(), tz).replace(
            hour=hour, minute=minute, second=second, microsecond=micros).timestamp()
            for day in dates for delta in (-1, 0, 1)]
        epochs = [r['epoch'] for r in rows]
        return min(candidates, key=lambda e: nearest(rows, epochs, e)[1]), 'UNVERIFIED local-time date inferred'
    except (ValueError, OverflowError, OSError):
        return None, 'UNVERIFIED invalid local time'


def known_counter(row, output, key, value, verified):
    if verified and number(value):
        row[key] = value
        observe(output, key, value, row['epoch'])


def profile_report(root, rows, destination, heap_destination, tolerance, tz):
    counts = {'lines': 0, 'matched': 0, 'unmatched': 0, 'inferredDate': 0, 'malformed': 0,
              'verifiedVmaSamples': 0, 'verifiedBackendSamples': 0, 'baselineSamples': 0,
              'incompleteMatchedSamples': 0, 'relevantMalformed': 0, 'unmatchedCurrentSamples': 0}
    metrics, fingerprints, legacy_fingerprints, errors = {}, set(), set(), []
    heap_samples = {'minecraftVma': 0, 'minecraftVmaMemoryType': 0, 'driverHeapEstimate': 0}
    epochs = [r['epoch'] for r in rows]
    try:
        source = (root / 'atlas-profile.log').open(encoding='utf-8-sig', errors='replace')
    except OSError:
        return {**counts, 'status': 'UNVERIFIED', 'reason': 'atlas-profile.log unavailable', 'counters': metrics}
    with source, destination.open('w', newline='', encoding='utf-8') as out, heap_destination.open('w', newline='', encoding='utf-8') as heaps:
        writer = csv.DictWriter(out, fieldnames=PROFILE_FIELDS); writer.writeheader()
        heap_keys = ['line', 'epoch', 'alignment', 'owner', 'heap', 'memoryType', 'typeIndex', 'heapIndex', 'propertyFlags', 'hostVisible', 'deviceLocal', 'status', 'allocationBytes',
                     'blockBytes', 'allocationCount', 'blockCount', 'usageBytes', 'budgetBytes']
        heap_writer = csv.DictWriter(heaps, fieldnames=heap_keys); heap_writer.writeheader()
        for line_number, line in enumerate(source, 1):
            counts['lines'] += 1
            decoded, malformed = fields(line)
            if malformed:
                counts['malformed'] += 1
                if len(errors) < 20: errors.append({'line': line_number, 'fields': malformed})
            memory = decoded.get('mcMemory', {})
            core = decoded.get('core', {})
            epoch, time_source = profile_epoch(line, memory, rows, tz)
            paired, gap = nearest(rows, epochs, epoch)
            within = paired is not None and gap <= tolerance
            authoritative = within and time_source == 'mcMemory.timestampMillis' and not malformed
            alignment = 'MATCHED' if authoritative else 'UNVERIFIED'
            counts['matched' if authoritative else 'unmatched'] += 1
            if within and time_source.startswith('UNVERIFIED local'): counts['inferredDate'] += 1
            # Historical appended lines outside this evidence window are counted but not copied into its report.
            explicit_in_window = bool(rows and number(epoch) and time_source == 'mcMemory.timestampMillis' and
                                      epochs[0] - tolerance <= epoch <= epochs[-1] + tolerance)
            if not within and not explicit_in_window:
                continue
            if explicit_in_window and not within: counts['unmatchedCurrentSamples'] += 1
            if malformed: counts['relevantMalformed'] += 1
            marker = MARKER.search(line)
            preamble = line[:marker.start()] if marker else line
            baseline = bool(re.search(r'(?<!\S)kind=memory-baseline(?=\s|$)', preamble))
            if authoritative and baseline: counts['baselineSamples'] += 1
            row = {'line': line_number, 'epoch': epoch, 'timeSource': time_source, 'alignment': alignment,
                   'memoryEpoch': paired['epoch'] if paired else None, 'gapSeconds': gap,
                   'phase': paired.get('phase', 'unknown') if paired else 'unknown',
                   'alignmentReason': 'within tolerance; sequential samples' if authoritative else
                       'nearest memory sample exceeds tolerance' if not within else 'date inferred or malformed fields'}
            vma = memory.get('minecraftVma', {}) if isinstance(memory.get('minecraftVma'), dict) else {}
            heap = memory.get('javaHeap', {}) if isinstance(memory.get('javaHeap'), dict) else {}
            pools = memory.get('nioBufferPools', {}) if isinstance(memory.get('nioBufferPools'), dict) else {}
            backend = core.get('backendMemory', {}) if isinstance(core.get('backendMemory'), dict) else {}
            vma_total = vma.get('total', {}) if isinstance(vma.get('total'), dict) else {}
            vma_ok = vma.get('status') == 'VERIFIED' and allocation_counts(vma_total)
            backend_ok = all(integer(backend.get(k)) for k in ('allocatedBytes', 'retiredBufferBytes', 'retiredBufferCount',
                                                               'submittedFrames', 'completedFrames'))
            backend_ok = backend_ok and backend['retiredBufferBytes'] <= backend['allocatedBytes'] and backend['completedFrames'] <= backend['submittedFrames']
            row.update(vmaStatus='VERIFIED' if vma_ok else 'UNVERIFIED', heapStatus=heap.get('status', 'UNVERIFIED'),
                       backendStatus='OBSERVED' if backend_ok else 'NOT_EXPECTED_BASELINE' if baseline and not backend else 'UNVERIFIED')
            heap_ok = heap.get('status') == 'VERIFIED' and all(number(heap.get(k)) for k in ('usedBytes', 'committedBytes', 'maxBytes'))
            row['heapStatus'] = 'VERIFIED' if heap_ok else 'UNVERIFIED'
            if authoritative and not (vma_ok and (backend_ok or (baseline and not backend))): counts['incompleteMatchedSamples'] += 1
            if authoritative and vma_ok: counts['verifiedVmaSamples'] += 1
            if authoritative and backend_ok: counts['verifiedBackendSamples'] += 1
            for dest, key in [('vmaAllocationBytes', 'allocationBytes'), ('vmaBlockBytes', 'blockBytes'),
                              ('vmaAllocationCount', 'allocationCount'), ('vmaBlockCount', 'blockCount')]:
                known_counter(row, metrics, dest, vma_total.get(key), authoritative and vma_ok)
            known_counter(row, metrics, 'vmaQueryNs', vma.get('queryNs'), authoritative and vma_ok)
            for dest, key in [('javaUsedBytes', 'usedBytes'), ('javaCommittedBytes', 'committedBytes'), ('javaMaxBytes', 'maxBytes')]:
                known_counter(row, metrics, dest, heap.get(key), authoritative and heap_ok)
            for pool_name in ('direct', 'mapped'):
                pool = pools.get(pool_name, {}) if isinstance(pools.get(pool_name), dict) else {}
                pool_ok = pool.get('status') == 'VERIFIED' and all(number(pool.get(k)) for k in ('memoryUsedBytes', 'capacityBytes', 'count'))
                row[pool_name + 'Status'] = 'VERIFIED' if pool_ok else 'UNVERIFIED'
                for dest, key in [('MemoryUsedBytes', 'memoryUsedBytes'), ('CapacityBytes', 'capacityBytes'), ('Count', 'count')]:
                    known_counter(row, metrics, pool_name + dest, pool.get(key), authoritative and pool_ok)
            for dest, key in [('backendAllocatedBytes', 'allocatedBytes'), ('backendRetiredBytes', 'retiredBufferBytes'),
                              ('backendRetiredCount', 'retiredBufferCount'), ('backendSubmittedFrames', 'submittedFrames'),
                              ('backendCompletedFrames', 'completedFrames')]:
                known_counter(row, metrics, dest, backend.get(key), authoritative and backend_ok)
            writer.writerow(row)
            valid_heaps = {entry.get('heap') for entry in vma.get('heaps', []) if isinstance(entry, dict) and
                           integer(entry.get('heap')) and entry['heap'] < 16 and isinstance(entry.get('deviceLocal'), bool) and allocation_counts(entry)} if isinstance(vma.get('heaps'), list) else set()
            for owner, entries in [('minecraftVma', vma.get('heaps')), ('minecraftVmaMemoryType', vma.get('memoryTypes')),
                                   ('driverHeapEstimate', core.get('driverHeapEstimates'))]:
                if not isinstance(entries, list): continue
                for entry in entries:
                    if not isinstance(entry, dict): continue
                    expected = ('usageBytes', 'budgetBytes') if owner == 'driverHeapEstimate' else ('allocationBytes', 'blockBytes', 'allocationCount', 'blockCount')
                    valid = authoritative and (True if owner == 'driverHeapEstimate' else vma_ok) and all(integer(entry.get(k)) for k in expected)
                    valid = valid and integer(entry.get('heap')) and entry['heap'] < 16 and isinstance(entry.get('deviceLocal'), bool)
                    if owner != 'driverHeapEstimate': valid = valid and allocation_counts(entry)
                    if owner == 'minecraftVmaMemoryType':
                        index = entry.get('memoryType', entry.get('typeIndex'))
                        flags = entry.get('propertyFlags')
                        valid = valid and integer(index) and index < 32 and entry.get('heap') in valid_heaps and integer(flags) and isinstance(entry.get('hostVisible'), bool)
                        valid = valid and entry['hostVisible'] == bool(flags & 2) and entry['deviceLocal'] == bool(flags & 1)
                    if valid: heap_samples[owner] += 1
                    data = {k: entry.get(k) for k in heap_keys if k in entry}
                    data.update(line=line_number, epoch=epoch, alignment=alignment, owner=owner, status='OBSERVED' if valid else 'UNVERIFIED')
                    heap_writer.writerow(data)
            grid = core.get('voxgrid', {})
            cache = grid.get('genCache', {}) if isinstance(grid, dict) else {}
            if isinstance(cache, dict) and isinstance(cache.get('fingerprint'), str):
                (fingerprints if authoritative else legacy_fingerprints).add(cache['fingerprint'])
    return {**counts, 'status': 'OBSERVED' if counts['verifiedVmaSamples'] and counts['verifiedBackendSamples'] and
            not counts['incompleteMatchedSamples'] and not counts['relevantMalformed'] and not counts['unmatchedCurrentSamples'] else 'UNVERIFIED',
            'statusScope': 'Timestamp-aligned VMA total and runtime backend coverage; pre-world baseline has no expected backend. Other attribution coverage is reported separately.',
            'counters': metrics, 'observedCacheFingerprints': sorted(fingerprints), 'parseErrors': errors,
            'unverifiedLegacyCacheFingerprints': sorted(legacy_fingerprints),
            'cacheFingerprintScope': 'Only epoch-matched observations enter observedCacheFingerprints. Legacy inferred values are not evidence of current-run key changes.',
            'heapRowCoverage': {owner: {'status': 'OBSERVED' if count else 'UNVERIFIED', 'rows': count} for owner, count in heap_samples.items()},
            'caveat': 'Only timestamped matched samples enter counter summaries. Time-of-day-only rows remain UNVERIFIED; historical overlap cannot be excluded.'}


def fingerprint_parts(value):
    if not isinstance(value, str): return unverified('fingerprint parts unavailable')
    match = FINGERPRINT.fullmatch(value.strip())
    if not match: return unverified('malformed fingerprint parts')
    keys = ('build', 'program', 'ingest', 'map', 'extra', 'stages', 'stageCount', 'whole')
    return {'status': 'OBSERVED', **{k: int(v) if k == 'stageCount' else v.lower().zfill(16) for k, v in zip(keys, match.groups())}}


def final_fingerprints(root):
    result = []
    try:
        with (root / 'latest.log').open(encoding='utf-8-sig', errors='replace') as stream:
            for line_number, line in enumerate(stream, 1):
                if "cache set's key:" not in line: continue
                value = line.split("cache set's key:", 1)[1].strip()
                result.append({'line': line_number, 'source': 'latest.log final cache-key log', **fingerprint_parts(value)})
    except OSError:
        return [unverified('latest.log final fingerprints unavailable')]
    return result or [unverified('no final cache-key parts logged')]


def cache_report(root):
    before, after = (read_json(root / ('cache-state-' + phase + '.json')) for phase in ('before', 'after'))
    def inventory(value):
        sets = value.get('sets')
        if not isinstance(sets, list) or any(not isinstance(x, dict) or not isinstance(x.get('name'), str) or not integer(x.get('bytes')) for x in sets):
            return None
        found = {x['name']: x for x in sets}
        return found if len(found) == len(sets) else None
    left, right = inventory(before), inventory(after)
    if left is None or right is None:
        return {**unverified('cache inventories unavailable/malformed'), 'before': before, 'after': after}
    one, two = before.get('identity'), after.get('identity')
    identity = unverified('identity values unavailable')
    if isinstance(one, dict) and isinstance(two, dict):
        keys = one.keys() | two.keys()
        if keys and all(isinstance(one.get(k), str) and isinstance(two.get(k), str) for k in keys):
            identity = {'status': 'OBSERVED', 'changed': {k: {'before': one[k], 'after': two[k]} for k in sorted(keys) if one[k] != two[k]}}
    return {'status': 'OBSERVED', 'before': before, 'after': after, 'identityComparison': identity,
            'addedSets': sorted(right.keys() - left.keys()), 'removedSets': sorted(left.keys() - right.keys()),
            'sharedSetByteChanges': {k: right[k]['bytes'] - left[k]['bytes'] for k in sorted(left.keys() & right.keys())},
            'caveat': 'Directory inventory is not proof of which fingerprint was active, cache reuse, or why eviction occurred.'}


def fnv_file(path):
    result = FNV_OFFSET
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            for byte in block: result = ((result ^ byte) * FNV_PRIME) & MASK
    return format(result, 'x')


def validate_ingest(path):
    manifest = read_json(path)
    result = {'manifest': str(path), 'status': 'UNVERIFIED', 'earlyPartsAtSetIngest': fingerprint_parts(manifest.get('nativeFingerprintPartsAtSetIngest')),
              'programFnv64': manifest.get('programFnv64'), 'modelIdentityProcessLocal': manifest.get('modelIdentityProcessLocal')}
    try:
        if manifest.get('format') != 'atlas-ingest-le32-v1': raise ValueError('unsupported ingest format')
        words, states, biomes = (manifest.get(k) for k in ('words', 'states', 'biomes'))
        if not all(integer(x) for x in (words, states, biomes)) or states > 0x7fffffff or biomes > 0x7fffffff:
            raise ValueError('invalid counts')
        tint, materials = 2 + 2 * states, 2 + 2 * states + 2 * biomes
        if materials + states * biomes != words: raise ValueError('inconsistent total words')
        expected = {'ids': (2, 2, states), 'flags': (3, 2, states), 'tints': (tint, 1, 2 * biomes), 'materials': (materials, 1, states * biomes)}
        for name, layout in expected.items():
            component = manifest.get(name)
            if not isinstance(component, dict) or any(not integer(component.get(k)) or component[k] != value for k, value in zip(('offset', 'stride', 'count'), layout)):
                raise ValueError('invalid component layout: ' + name)
        binary = path.with_name('ingest.bin')
        if binary.stat().st_size != words * 4: raise ValueError('binary length mismatch')
        with binary.open('rb') as stream:
            if struct.unpack('<II', stream.read(8)) != (states, biomes): raise ValueError('binary header mismatch')
        claimed = manifest.get('fnv64')
        if not isinstance(claimed, str) or not re.fullmatch('[0-9a-fA-F]{1,16}', claimed): raise ValueError('invalid FNV hash')
        digest = fnv_file(binary)
        if int(digest, 16) != int(claimed, 16): raise ValueError('binary FNV mismatch')
        result.update(status='VERIFIED', binary=str(binary), fnv64=digest, words=words, states=states, biomes=biomes,
                      layout={name: dict(zip(('offset', 'stride', 'count'), layout)) for name, layout in expected.items()})
    except (OSError, ValueError, struct.error) as error:
        result['reason'] = str(error)
    return result


def ingest_reports(root, selected=None):
    if selected:
        path = (root / selected).resolve()
        if not path.is_relative_to(root.resolve()): return [unverified('selected ingest path outside evidence')]
        return [validate_ingest(path)]
    return [validate_ingest(path) for path in sorted(root.rglob('ingest.json'))] or [unverified('no ingest manifest found')]


def component_at(word, manifest):
    if word < 2: return 'header', word
    for name in COMPONENTS:
        layout = manifest['layout'][name]
        delta = word - layout['offset']
        if delta >= 0 and delta % layout['stride'] == 0 and delta // layout['stride'] < layout['count']:
            return name, delta // layout['stride']
    return 'outside layout', word


def compare_ingest(left, right):
    if len(left) != 1 or len(right) != 1 or any(x.get('status') != 'VERIFIED' for x in left + right):
        return unverified('need one valid ingest attempt per evidence directory; use --ingest/--compare-ingest for ambiguous attempts')
    a, b = left[0], right[0]
    result = {'status': 'OBSERVED', 'leftManifest': a['manifest'], 'rightManifest': b['manifest'],
              'earlyPartsAtSetIngest': {'left': a['earlyPartsAtSetIngest'], 'right': b['earlyPartsAtSetIngest']},
              'caveat': 'Process-local modelIdentity is not compared as a stable content key; early setIngest parts are not final fingerprints.'}
    with Path(a['binary']).open('rb') as one, Path(b['binary']).open('rb') as two:
        word = 0
        while True:
            x, y = one.read(65536), two.read(65536)
            if x != y:
                for offset in range(0, max(len(x), len(y)), 4):
                    xx, yy = x[offset:offset + 4], y[offset:offset + 4]
                    if xx == yy: continue
                    index = word + offset // 4
                    result.update(equalBytes=False, firstDifferentWord=index,
                                  leftComponent=component_at(index, a), rightComponent=component_at(index, b),
                                  leftValue=struct.unpack('<I', xx)[0] if len(xx) == 4 else None,
                                  rightValue=struct.unpack('<I', yy)[0] if len(yy) == 4 else None)
                    return result
            if not x: return {**result, 'equalBytes': True}
            word += len(x) // 4


def cleanup_report(rows, receipt):
    cleanup = [r for r in rows if r.get('phase') == 'after-client-cleanup']
    observed, unknown = [], []
    if cleanup:
        processes = cleanup[-1].get('processes')
        for process in processes if isinstance(processes, list) else []:
            if not isinstance(process, dict) or not process.get('client'): continue
            (observed if process_status(process).startswith('OBSERVED') else unknown).append(process.get('pid'))
    return {'status': 'OBSERVED' if cleanup else 'UNVERIFIED', 'sampleEpoch': cleanup[-1]['epoch'] if cleanup else None,
            'clientClosedReported': receipt.get('client_closed'), 'launchResolvedReported': receipt.get('launch_resolved'),
            'forcedCleanupReported': receipt.get('forced_cleanup'), 'namedClientPidsInLastCleanupSample': observed,
            'unknownOrExitingClientPidsInLastCleanupSample': unknown,
            'currentLiveness': 'UNVERIFIED: snapshots and supervisor receipt do not establish current process liveness; unknown/exiting is not remaining live.'}


def compare_final_parts(left, right):
    keys = ('build', 'program', 'ingest', 'map', 'extra', 'stages', 'stageCount', 'whole')
    def unique(entries):
        return {tuple(e.get(k) for k in keys) for e in entries if e.get('status') == 'OBSERVED'}
    a, b = unique(left), unique(right)
    if len(a) != 1 or len(b) != 1 or any(e.get('status') != 'OBSERVED' for e in left + right):
        return unverified('need one distinct final fingerprint per run')
    x, y = dict(zip(keys, next(iter(a)))), dict(zip(keys, next(iter(b))))
    return {'status': 'OBSERVED', 'changed': {k: {'left': x[k], 'right': y[k]} for k in keys if x[k] != y[k]}}


def termination_report(root, rows, receipt):
    markers = []
    pattern = re.compile(r'(?:^|\s)(?:java\.lang\.)?OutOfMemoryError(?::|\s)|\bVK_ERROR_OUT_OF_(?:HOST|DEVICE)_MEMORY\b')
    available = True
    try:
        with (root / 'latest.log').open(encoding='utf-8-sig', errors='replace') as stream:
            for line_number, line in enumerate(stream, 1):
                if pattern.search(line) and len(markers) < 20: markers.append({'line': line_number, 'marker': pattern.search(line)[0].strip()})
    except OSError:
        available = False
    return {'supervisorStateReported': receipt.get('state'), 'memoryPressureReported': receipt.get('memory_pressure'),
            'forcedCleanupReported': receipt.get('forced_cleanup'), 'workerRcReported': receipt.get('worker_rc'),
            'elapsedSecondsReported': receipt.get('elapsed_seconds'),
            'sampleWindow': {'firstEpoch': rows[0]['epoch'], 'lastEpoch': rows[-1]['epoch'],
                             'seconds': rows[-1]['epoch'] - rows[0]['epoch']} if rows else unverified('no memory samples'),
            'oomLogEvidence': {'status': 'LOG_MARKERS' if markers else 'UNVERIFIED', 'latestLogAvailable': available,
                               'markers': markers, 'caveat': 'Log markers are observations, not a proven termination cause. Their absence cannot prove no OOM or OS dialog. A memory-pressure guard stop is not itself an OOM.'}}


def analyze(root, *, compare=None, tolerance=3.0, utc_offset=None, ingest=None, compare_ingest_path=None):
    root = Path(root).resolve()
    if not root.is_dir(): raise ValueError('evidence directory does not exist')
    if not number(tolerance) or tolerance <= 0: raise ValueError('positive finite alignment tolerance required')
    tz = utc_offset or datetime.now().astimezone().tzinfo
    destination = root / ('memory-report-' + datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8])
    destination.mkdir()
    rows, errors = memory_rows(root)
    profile = profile_report(root, rows, destination / 'profile.csv', destination / 'profile-heaps.csv', tolerance, tz)
    cache, ingests = cache_report(root), ingest_reports(root, ingest)
    receipt = read_json(root / 'supervisor-receipt.json')
    cpu = cpu_report(rows, destination / 'processes.csv')
    report = {'status': 'OBSERVED' if rows and not errors and profile['status'] == cpu['status'] == 'OBSERVED' else 'UNVERIFIED',
              'evidence': str(root), 'reportDirectory': str(destination), 'memorySamples': len(rows), 'memoryErrors': errors,
              'alignmentToleranceSeconds': tolerance, 'localTimeInferenceZone': str(tz),
              'cpu': cpu, 'profile': profile, 'cacheInventory': cache,
              'ingestAttempts': ingests, 'finalFingerprintParts': final_fingerprints(root),
              'cleanup': cleanup_report(rows, receipt),
              'termination': termination_report(root, rows, receipt),
              'limitations': ['No memory leak/fix or performance pass is inferred.', 'GPU allocation bytes are not process-private bytes and are not added to CPU totals.',
                             'Backend retired bytes are a subset of backend allocated bytes.', 'MC VMA blockBytes contain its allocationBytes; both are GPU allocator ownership.',
                             'Driver heap estimates overlap other shared-device owners and are not host-private bytes.',
                             'JVM/NIO do not account for all FFM, native malloc or driver host allocations.',
                             'Samples are sequential; pairings within tolerance are not atomic or proof of causality.']}
    if compare:
        other = Path(compare).resolve()
        report['comparison'] = {'evidence': str(other), 'ingest': compare_ingest(ingests, ingest_reports(other, compare_ingest_path)),
                                'finalFingerprintParts': {'left': report['finalFingerprintParts'], 'right': final_fingerprints(other)},
                                'cacheInventory': {'left': cache, 'right': cache_report(other)}}
        comparison = report['comparison']
        comparison['finalFingerprintParts']['difference'] = compare_final_parts(
            comparison['finalFingerprintParts']['left'], comparison['finalFingerprintParts']['right'])
        if comparison['ingest']['status'] == 'UNVERIFIED': report['status'] = 'UNVERIFIED'
    (destination / 'summary.json').write_text(json.dumps(report, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    return report


def offset(value):
    match = re.fullmatch(r'([+-])(\d\d):(\d\d)', value)
    if not match or int(match[2]) > 23 or int(match[3]) > 59: raise argparse.ArgumentTypeError('expected signed UTC offset HH:MM')
    return timezone((1 if match[1] == '+' else -1) * timedelta(hours=int(match[2]), minutes=int(match[3])))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path)
    parser.add_argument('--compare', type=Path)
    parser.add_argument('--tolerance-seconds', type=float, default=3.0)
    parser.add_argument('--utc-offset', type=offset)
    parser.add_argument('--ingest', help='one ingest.json path relative to evidence, when multiple attempts exist')
    parser.add_argument('--compare-ingest', help='one ingest.json path relative to comparison evidence')
    args = parser.parse_args(argv)
    if args.compare_ingest and not args.compare: parser.error('--compare-ingest requires --compare')
    try:
        report = analyze(args.evidence, compare=args.compare, tolerance=args.tolerance_seconds, utc_offset=args.utc_offset,
                         ingest=args.ingest, compare_ingest_path=args.compare_ingest)
    except (OSError, ValueError) as error:
        parser.exit(2, 'UNVERIFIED: ' + str(error) + '\n')
    print(json.dumps({k: report[k] for k in ('status', 'reportDirectory', 'memorySamples')}, indent=2))
    return 0 if report['status'] == 'OBSERVED' else 2


if __name__ == '__main__':
    raise SystemExit(main())
