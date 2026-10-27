"""CPU-only evidence-oracle regressions. No launcher, native renderer or GPU is used."""
import csv
from datetime import timezone, timedelta
import json
from pathlib import Path
import struct
import tempfile
import unittest

import atlas_memory_report as report

EPOCH = 1793068100.0
COUNTS = {'allocationBytes': 0, 'blockBytes': 4096, 'allocationCount': 0, 'blockCount': 1}


def memory(epoch=EPOCH, phase='running', name='java.exe', handles=3, private=100):
    return {'epoch': epoch, 'phase': phase, 'job': {'sampledPrivateBytes': private},
            'processes': [{'pid': 42, 'name': name, 'handles': handles, 'privateBytes': private,
                           'workingSetBytes': private // 2, 'client': True, 'inJob': True}]}


def telemetry(epoch=EPOCH):
    return {'timestampMillis': int(epoch * 1000),
            'minecraftVma': {'status': 'VERIFIED', 'total': COUNTS.copy(), 'queryNs': 0,
                             'heaps': [{**COUNTS, 'heap': 0, 'deviceLocal': True}],
                             'memoryTypes': [{**COUNTS, 'memoryType': 2, 'heap': 0, 'propertyFlags': 7,
                                              'hostVisible': True, 'deviceLocal': True}]},
            'javaHeap': {'status': 'VERIFIED', 'usedBytes': 10, 'committedBytes': 20, 'maxBytes': 30},
            'nioBufferPools': {'direct': {'status': 'VERIFIED', 'memoryUsedBytes': 0, 'capacityBytes': 0, 'count': 0},
                               'mapped': {'status': 'UNVERIFIED', 'reason': 'pool_unavailable'}}}


def core():
    return {'backendMemory': {'allocatedBytes': 5000, 'retiredBufferBytes': 1000, 'retiredBufferCount': 1,
                              'submittedFrames': 5, 'completedFrames': 4},
            'driverHeapEstimates': [{'heap': 0, 'deviceLocal': True, 'usageBytes': 6000, 'budgetBytes': 9999}]}


def write_fixture(root, rows=None, lines=None):
    root.mkdir(parents=True, exist_ok=True)
    (root / 'memory.jsonl').write_text(''.join(json.dumps(x) + '\n' for x in (rows or [memory()])))
    (root / 'atlas-profile.log').write_text('\n'.join(lines or [
        '22:28:20.0 core=' + json.dumps(core()) + ' recorder{on} mcMemory=' + json.dumps(telemetry())]) + '\n')
    for phase in ('before', 'after'):
        (root / ('cache-state-' + phase + '.json')).write_text(json.dumps({'sets': [], 'identity': {'program': 'a'}}))
    (root / 'supervisor-receipt.json').write_text(json.dumps({'client_closed': True, 'launch_resolved': True}))


def ingest(root, words=None, *, attempt='ingest-a'):
    words = words or [2, 1, 5, 2, 6, 3, 0xaabbcc, 0x112233, 10, 11]
    directory = root / attempt; directory.mkdir(parents=True)
    binary = directory / 'ingest.bin'; binary.write_bytes(struct.pack('<' + 'I' * len(words), *words))
    states, biomes = words[:2]
    tint, materials = 2 + states * 2, 2 + states * 2 + biomes * 2
    manifest = {'format': 'atlas-ingest-le32-v1', 'words': len(words), 'states': states, 'biomes': biomes,
                'fnv64': report.fnv_file(binary), 'programFnv64': 'abc',
                'nativeFingerprintPartsAtSetIngest': 'build 1 program 2 ingest 3 map 0 extra 0 stages 0 (0) whole 4'}
    for name, layout in {'ids': (2, 2, states), 'flags': (3, 2, states), 'tints': (tint, 1, biomes * 2),
                         'materials': (materials, 1, states * biomes)}.items():
        manifest[name] = dict(zip(('offset', 'stride', 'count'), layout))
    path = directory / 'ingest.json'; path.write_text(json.dumps(manifest))
    return path


class MemoryReportTest(unittest.TestCase):
    def test_decoder_skips_nested_strings_braces_and_future_fields(self):
        value = {'note': 'literal } core={ mcMemory={ fake', 'nested': {'future': [1, {'a': '}'}]}}
        got, bad = report.fields('core= ' + json.dumps(value) + ' tail mcMemory=' + json.dumps(telemetry()))
        self.assertFalse(bad)
        self.assertEqual(got['core'], value)
        self.assertEqual(got['mcMemory']['minecraftVma']['memoryTypes'][0]['memoryType'], 2)
        got, bad = report.fields('core={} core={"x":" mcMemory={}"} mcMemory={"timestampMillis":0}')
        self.assertIn('core', bad)
        self.assertEqual(got['mcMemory']['timestampMillis'], 0)

    def test_authoritative_epoch_zero_and_overlapping_owners_stay_distinct(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); write_fixture(root)
            result = report.analyze(root)
            self.assertEqual(result['status'], 'OBSERVED')
            counters = result['profile']['counters']
            self.assertEqual(counters['vmaAllocationBytes']['max'], 0)
            self.assertEqual(counters['vmaBlockBytes']['max'], 4096)
            self.assertEqual(counters['backendAllocatedBytes']['max'], 5000)
            self.assertEqual(counters['backendRetiredBytes']['max'], 1000)
            self.assertNotIn('explainedPrivateBytes', result)
            with (Path(result['reportDirectory']) / 'profile.csv').open() as stream: row = next(csv.DictReader(stream))
            self.assertEqual(row['gapSeconds'], '0.0')
            self.assertEqual(row['directMemoryUsedBytes'], '0')
            self.assertEqual(row['mappedMemoryUsedBytes'], '')
            with (Path(result['reportDirectory']) / 'profile-heaps.csv').open() as stream: rows = list(csv.DictReader(stream))
            self.assertEqual({r['owner'] for r in rows}, {'minecraftVma', 'minecraftVmaMemoryType', 'driverHeapEstimate'})
            self.assertEqual(next(r for r in rows if r['owner'] == 'minecraftVmaMemoryType')['hostVisible'], 'True')

    def test_excessive_gap_is_reported_but_not_admitted_to_summary(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            write_fixture(root, rows=[memory(EPOCH - 10), memory(EPOCH + 10)])
            result = report.analyze(root, tolerance=3)
            self.assertEqual(result['status'], 'UNVERIFIED')
            self.assertFalse(result['profile']['counters'])
            with (Path(result['reportDirectory']) / 'profile.csv').open() as stream: row = next(csv.DictReader(stream))
            self.assertEqual(float(row['gapSeconds']), 10)
            self.assertEqual(row['alignment'], 'UNVERIFIED')

    def test_pre_world_baseline_does_not_require_native_backend(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            write_fixture(root, rows=[memory(EPOCH - 1), memory()], lines=[
                'kind=memory-baseline mcMemory=' + json.dumps(telemetry(EPOCH - 1)),
                'core=' + json.dumps(core()) + ' mcMemory=' + json.dumps(telemetry())])
            result = report.analyze(root)
            self.assertEqual(result['status'], 'OBSERVED')
            self.assertEqual(result['profile']['baselineSamples'], 1)
            self.assertEqual(result['profile']['incompleteMatchedSamples'], 0)
            with (Path(result['reportDirectory']) / 'profile.csv').open() as stream: rows = list(csv.DictReader(stream))
            self.assertEqual(rows[0]['backendStatus'], 'NOT_EXPECTED_BASELINE')

    def test_impossible_counters_and_wrong_type_flags_are_unverified(self):
        for key, value in [('retiredBufferBytes', 5001), ('completedFrames', 6)]:
            with self.subTest(key=key), tempfile.TemporaryDirectory() as folder:
                root = Path(folder); bad = core(); bad['backendMemory'][key] = value
                write_fixture(root, lines=['core=' + json.dumps(bad) + ' mcMemory=' + json.dumps(telemetry())])
                result = report.analyze(root)
                self.assertEqual(result['status'], 'UNVERIFIED')
                self.assertNotIn('backendAllocatedBytes', result['profile']['counters'])
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); bad = telemetry(); bad['minecraftVma']['memoryTypes'][0]['propertyFlags'] = 1
            write_fixture(root, lines=['core=' + json.dumps(core()) + ' mcMemory=' + json.dumps(bad)])
            result = report.analyze(root)
            self.assertEqual(result['profile']['heapRowCoverage']['minecraftVmaMemoryType']['status'], 'UNVERIFIED')

    def test_legacy_date_inference_and_missing_telemetry_never_verified(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); write_fixture(root, lines=['22:28:20.0 core=' + json.dumps(core())])
            result = report.analyze(root, utc_offset=timezone(timedelta(hours=-4)))
            self.assertEqual(result['status'], 'UNVERIFIED')
            self.assertEqual(result['profile']['inferredDate'], 1)
            self.assertFalse(result['profile']['counters'])

    def test_appended_legacy_key_is_not_current_run_fingerprint_drift(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); legacy, current = core(), core()
            legacy['voxgrid'] = {'genCache': {'fingerprint': 'old-session-key'}}
            current['voxgrid'] = {'genCache': {'fingerprint': 'current-run-key'}}
            write_fixture(root, lines=[
                '22:28:20.0 core=' + json.dumps(legacy),
                '22:28:20.0 core=' + json.dumps(current) + ' mcMemory=' + json.dumps(telemetry())])
            result = report.analyze(root, utc_offset=timezone(timedelta(hours=-4)))
            self.assertEqual(result['profile']['observedCacheFingerprints'], ['current-run-key'])
            self.assertEqual(result['profile']['unverifiedLegacyCacheFingerprints'], ['old-session-key'])
            self.assertEqual(result['profile']['verifiedVmaSamples'], 1)

    def test_null_malformed_nonfinite_and_partial_samples_are_not_zero(self):
        for invalid in ['null', '{broken}', '{"timestampMillis":NaN}']:
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as folder:
                root = Path(folder); write_fixture(root, lines=['22:28:20 core=' + json.dumps(core()) + ' mcMemory=' + invalid])
                result = report.analyze(root, utc_offset=timezone(timedelta(hours=-4)))
                self.assertEqual(result['status'], 'UNVERIFIED')
                self.assertFalse(result['profile']['counters'])
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); bad = telemetry(EPOCH + 1); bad['minecraftVma'] = {'status': 'UNVERIFIED'}
            write_fixture(root, lines=['core=' + json.dumps(core()) + ' mcMemory=' + json.dumps(t) for t in [telemetry(), bad]])
            result = report.analyze(root)
            self.assertEqual(result['status'], 'UNVERIFIED')
            self.assertEqual(result['profile']['verifiedVmaSamples'], 1)
            self.assertEqual(result['profile']['incompleteMatchedSamples'], 1)
        self.assertFalse(report.number(True)); self.assertFalse(report.number(float('nan')))

    def test_cleanup_unknown_exiting_never_becomes_remaining_live(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            write_fixture(root, rows=[memory(private=100), memory(EPOCH + 1, private=150),
                memory(EPOCH + 3, 'after-client-cleanup', name='unknown', handles=0, private=80)])
            result = report.analyze(root)
            cleanup = result['cleanup']
            self.assertTrue(cleanup['clientClosedReported'])
            self.assertEqual(cleanup['namedClientPidsInLastCleanupSample'], [])
            self.assertEqual(cleanup['unknownOrExitingClientPidsInLastCleanupSample'], [42])
            self.assertTrue(cleanup['currentLiveness'].startswith('UNVERIFIED'))
            counter = result['cpu']['observedNamedClientCounters']['client.42.privateBytes']
            self.assertEqual(counter['growth'], 50)
            self.assertEqual(counter['max'], 150)

    def test_ingest_independent_fnv_header_layout_length_and_tampering(self):
        # Known Java offset basis, deliberately different from the more usual FNV-1a offset with a final 7.
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); path = ingest(root)
            expected = 1469598103934665603
            for b in path.with_name('ingest.bin').read_bytes(): expected = ((expected ^ b) * 1099511628211) & ((1 << 64) - 1)
            self.assertEqual(int(json.loads(path.read_text())['fnv64'], 16), expected)
            self.assertEqual(report.validate_ingest(path)['status'], 'VERIFIED')
            value = json.loads(path.read_text()); value['flags']['stride'] = 1; path.write_text(json.dumps(value))
            self.assertEqual(report.validate_ingest(path)['status'], 'UNVERIFIED')
        for kind in ('length', 'header', 'hash'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as folder:
                path = ingest(Path(folder)); binary = path.with_name('ingest.bin'); data = bytearray(binary.read_bytes())
                if kind == 'length': data.pop()
                else: data[0 if kind == 'header' else -1] ^= 1
                binary.write_bytes(data)
                self.assertEqual(report.validate_ingest(path)['status'], 'UNVERIFIED')

    def test_first_different_component_and_word_after_validating_both_attempts(self):
        original = [2, 1, 5, 2, 6, 3, 0xaabbcc, 0x112233, 10, 11]
        for index, component in [(2, 'ids'), (3, 'flags'), (6, 'tints'), (8, 'materials')]:
            with self.subTest(index=index), tempfile.TemporaryDirectory() as folder:
                root = Path(folder); a = ingest(root / 'one', original)
                altered = original.copy(); altered[index] += 1; b = ingest(root / 'two', altered)
                result = report.compare_ingest([report.validate_ingest(a)], [report.validate_ingest(b)])
                self.assertFalse(result['equalBytes'])
                self.assertEqual(result['firstDifferentWord'], index)
                self.assertEqual(result['leftComponent'][0], component)
        with tempfile.TemporaryDirectory() as folder:
            path = ingest(Path(folder)); valid = report.validate_ingest(path)
            self.assertTrue(report.compare_ingest([valid], [valid])['equalBytes'])
            self.assertEqual(report.compare_ingest([valid, valid], [valid])['status'], 'UNVERIFIED')

    def test_report_directories_are_fresh_inputs_unchanged_and_final_parts_separate(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder) / 'one'; other = Path(folder) / 'two'
            for directory in (root, other):
                write_fixture(directory); ingest(directory)
                (directory / 'latest.log').write_text("atlas coarse bake: the cache set's key: build 1 program 2 ingest 3 map 4 extra 5 stages 6 (17) whole 7\n")
            before = {str(p): p.read_bytes() for p in root.rglob('*') if p.is_file()}
            one = report.analyze(root, compare=other); two = report.analyze(root)
            self.assertNotEqual(one['reportDirectory'], two['reportDirectory'])
            self.assertEqual(before, {p: Path(p).read_bytes() for p in before})
            self.assertTrue(one['comparison']['ingest']['equalBytes'])
            early = one['ingestAttempts'][0]['earlyPartsAtSetIngest']
            final = one['finalFingerprintParts'][0]
            self.assertEqual(early['stageCount'], 0)
            self.assertEqual(final['stageCount'], 17)
            self.assertEqual(one['comparison']['finalFingerprintParts']['difference']['changed'], {})
            value = json.loads((root / 'cache-state-after.json').read_text()); value['sets'] = [{'name': 'seed/gen-new', 'bytes': 5}]
            (root / 'cache-state-after.json').write_text(json.dumps(value))
            self.assertEqual(report.cache_report(root)['addedSets'], ['seed/gen-new'])

    def test_bad_memory_json_and_invalid_tolerance_remain_unverified(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); write_fixture(root)
            with (root / 'memory.jsonl').open('a') as stream: stream.write('{broken}\nnull\n')
            result = report.analyze(root)
            self.assertEqual(result['status'], 'UNVERIFIED')
            self.assertEqual(len(result['memoryErrors']), 2)
            for invalid in (0, float('nan'), -1):
                with self.assertRaises(ValueError): report.analyze(root, tolerance=invalid)

    def test_pressure_stop_and_oom_log_markers_are_different_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); write_fixture(root)
            receipt = {'state': 'UNVERIFIED', 'memory_pressure': True, 'forced_cleanup': True, 'elapsed_seconds': 132.3}
            (root / 'supervisor-receipt.json').write_text(json.dumps(receipt))
            (root / 'latest.log').write_text('JVM option -XX:+ExitOnOutOfMemoryError\n')
            result = report.analyze(root)['termination']
            self.assertTrue(result['memoryPressureReported'])
            self.assertEqual(result['elapsedSecondsReported'], 132.3)
            self.assertEqual(result['oomLogEvidence']['status'], 'UNVERIFIED')
            self.assertEqual(result['oomLogEvidence']['markers'], [])
            (root / 'latest.log').write_text('Render thread java.lang.OutOfMemoryError: allocation refused\n')
            self.assertEqual(report.analyze(root)['termination']['oomLogEvidence']['status'], 'LOG_MARKERS')


if __name__ == '__main__':
    unittest.main()
