import importlib.util
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('gen_shutdown_probe', Path(__file__).with_name('gen_shutdown_probe.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def clean(case):
    lines = ['input-preflight-complete before-Vulkan', 'generator-destroy-begin', 'generator-destroy-end',
             'no-more-frames', 'context-destroy-begin', 'context-destroy-end']
    if case.startswith('borrowed-'):
        lines += ['actual-copy-complete', 'token-released', 'external-device-destroy-begin',
                  'external-device-destroy-end', "validation (the test's device, atlas on it): 0 errors"]
    else: lines += ['actual-owned-readback-complete', '[vulkan] validation on']
    if not case.endswith('-far'): lines += ['near-witness status=0 flags=0 requested=100 acceptedMask=100 sent=1']
    if not case.endswith('-near'): lines += ['far-witness status=0 mixed=2 eligibleMixed=1 eligible=20 taken=20 givenBefore=0 givenAfter=20 flags=0']
    return '\n'.join(lines)


class ShutdownOracleTest(unittest.TestCase):
    def test_actual_update_requires_new_snapshot_true_stage_and_real_submission_after_seal(self):
        text = '\n'.join(['input-preflight-complete before-Vulkan', 'actual-queue-allocation aliases-rejected',
                          'admitted-update primed lodCount=1 regions=1 revision=1 actual-upload-complete',
                          'admitted-update paused in taken stageScene branch lodCount=1 revision=2',
                          'admitted-update timeout status=-8 elapsed=5.001',
                          'admitted scene-upload submitted revision=2',
                          'admitted-update staged lodCount=1 previous=1 selected=2 reached=2 sealed=1 uploaded=1',
                          'admitted-update joined status=0', 'DRAINED preAttach=1; post-close-admission-denied',
                          'native queue tail completed; frame and upload admission sealed',
                          'pre-attach context-destroy-end', 'pre-attach external-device-destroy-end validation=0'])
        self.assertEqual(probe.assess(text, 'borrowed-both', 0, None, True, close_mode='pre-update')['state'], 'CLEAN_SHUTDOWN_OBSERVED')
        for good, bad in [('lodCount=1 previous', 'lodCount=0 previous'), ('previous=1', 'previous=2'),
                          ('regions=1', 'regions=0'), ('revision=1 actual-upload-complete', 'revision=0 actual-upload-complete'),
                          ('reached=2', 'reached=0'), ('uploaded=1', 'uploaded=0'),
                          ('admitted scene-upload submitted revision=2', 'not submitted'),
                          ('admitted scene-upload submitted revision=2', 'admitted scene-upload submitted revision=22'),
                          ('elapsed=5.001', 'elapsed=0.001')]:
            self.assertEqual(probe.assess(text.replace(good,bad), 'borrowed-both', 0, None, True,
                                         close_mode='pre-update')['state'], 'UNVERIFIED')

    def test_producer_failure_is_blocked_ownership_not_clean_shutdown(self):
        text = '\n'.join(['input-preflight-complete before-Vulkan', 'actual-queue-allocation aliases-rejected',
                          'pre-attach real producer destroy',
                          'blocked status=-4 preAttach=1; token-null; admission-denied; legacy-owner-retained',
                          'incomplete cleanup: refusing destroy with live/failed generator owners',
                          'incomplete cleanup: generator 0x123 retained after close preparation failed: injected producer close refusal; generator ownership retained',
                          'blocked validation=0; resources-not-released; host-device-close-blocked',
                          'EXPECTED_BLOCKED producer; host-device-destroy-not-reached'])
        result = probe.assess(text, 'borrowed-both', 0, None, True, close_mode='pre-producer')
        self.assertEqual(result['state'], 'EXPECTED_CLOSE_BLOCKED', result)
        for forbidden in ('context-destroy-begin', 'external-device-destroy-begin', 'token-released', 'VUID-05137'):
            self.assertEqual(probe.assess(text + '\n' + forbidden, 'borrowed-both', 0, None, True,
                                         close_mode='pre-producer')['state'], 'FAILED')

    def test_producer_missing_status_or_timeout_does_not_confirm_blocking(self):
        text = 'EXPECTED_BLOCKED producer; host-device-destroy-not-reached'
        self.assertEqual(probe.assess(text, 'borrowed-both', 0, None, True, close_mode='pre-producer')['state'], 'UNVERIFIED')
        self.assertEqual(probe.assess(text, 'borrowed-both', -1, 'timeout', True, close_mode='pre-producer')['state'], 'UNVERIFIED')

    def test_consumer_retry_requires_actual_drain_and_clean_host_close(self):
        text = clean('borrowed-both') + '\n' + '\n'.join([
            'actual-queue-allocation aliases-rejected', 'DRAINED preAttach=0; post-close-admission-denied',
            'native queue tail completed; frame and upload admission sealed',
            'blocked status=-8; token-null; admission-denied; legacy-owner-retained',
            'consumer retry retains the same real queue-tail fence',
            'incomplete cleanup: refusing destroy of SEALED, undrained context'])
        self.assertEqual(probe.assess(text, 'borrowed-both', 0, None, True, close_mode='consumer')['state'], 'CLEAN_SHUTDOWN_OBSERVED')
        self.assertEqual(probe.assess(text.replace('native queue tail completed', 'not proved'), 'borrowed-both', 0,
                                     None, True, close_mode='consumer')['state'], 'UNVERIFIED')
        self.assertEqual(probe.assess(text + '\nVUID-descriptor', 'borrowed-both', 0, None, True,
                                     close_mode='consumer')['state'], 'FAILED')

    def test_actual_lock_deadline_required_before_retry_acceptance(self):
        text = '\n'.join(['input-preflight-complete before-Vulkan', 'actual-queue-allocation aliases-rejected',
                          'DRAINED preAttach=1; post-close-admission-denied',
                          'native queue tail completed; frame and upload admission sealed',
                          'pre-attach context-destroy-end', 'pre-attach external-device-destroy-end validation=0'])
        for duration in ('0.001', '6.500'):
            self.assertEqual(probe.assess(text + '\nbounded real-lock=3 elapsed=' + duration, 'borrowed-both', 0,
                                         None, True, close_mode='pre-queue-lock')['state'], 'UNVERIFIED')
        self.assertEqual(probe.assess(text + '\nbounded real-lock=3 elapsed=5.001', 'borrowed-both', 0,
                                     None, True, close_mode='pre-queue-lock')['state'], 'CLEAN_SHUTDOWN_OBSERVED')

    def test_cache_pair_exact_copy_originals_unchanged_and_driver_clone_write_separate(self):
        with tempfile.TemporaryDirectory(prefix='shutdown-cpu-', dir=probe.ROOT / '.work' / 'codex') as temp:
            root = Path(temp); base = root / 'source.bin'
            header = struct.pack('<IIII16s', 32, 1, 0x10de, 0x2204, bytes(range(16)))
            base.write_bytes(header + b'renderer')
            Path(str(base) + '.gen').write_bytes(header + b'generator')
            records = probe.pipeline_cache_inputs([str(base)])
            before = [probe.sha(row['path']) for row in records['originals']]
            cloned = probe.clone_pipeline_cache(records, root / 'clone')
            self.assertEqual([row['sha256'] for row in cloned['clonesBefore']], before)
            Path(cloned['base']).write_bytes(header + b'legitimate driver rewrite')
            self.assertEqual([probe.sha(row['path']) for row in records['originals']], before)
            self.assertNotEqual(probe.cache_file(cloned['base'])['sha256'], before[0])
            with self.assertRaises(FileExistsError): probe.clone_pipeline_cache(records, root / 'clone')

    def test_cache_header_oversize_missing_and_pair_device_mismatch_refused(self):
        with tempfile.TemporaryDirectory(prefix='shutdown-cpu-', dir=probe.ROOT / '.work' / 'codex') as temp:
            root = Path(temp); base = root / 'cache'
            header = struct.pack('<IIII16s', 32, 1, 0x10de, 0x2204, bytes(range(16)))
            for length, version, vendor, device, uuid in [(0, 1, 1, 1, bytes(range(16))),
                    (32, 2, 1, 1, bytes(range(16))), (32, 1, 0, 1, bytes(range(16))), (32, 1, 1, 1, bytes(16))]:
                base.write_bytes(struct.pack('<IIII16s', length, version, vendor, device, uuid))
                with self.assertRaises(ValueError): probe.cache_file(base)
            with base.open('wb') as stream: stream.truncate(probe.CACHE_LIMIT + 1)
            with self.assertRaises(ValueError): probe.cache_file(base)
            base.write_bytes(header)
            with self.assertRaises(FileNotFoundError): probe.pipeline_cache_inputs([str(base)])
            Path(str(base) + '.gen').write_bytes(struct.pack('<IIII16s', 32, 1, 0x10de, 0x2205, bytes(range(16))))
            with self.assertRaises(ValueError): probe.pipeline_cache_inputs([str(base)])

    def test_ambiguous_directory_cache_and_missing_actual_device_proof_refused(self):
        with tempfile.TemporaryDirectory(prefix='shutdown-cpu-', dir=probe.ROOT / '.work' / 'codex') as temp:
            with self.assertRaises(ValueError): probe.pipeline_cache_inputs([temp, temp])
            with self.assertRaises(ValueError): probe.pipeline_cache_inputs([temp])
        self.assertEqual(probe.assess(clean('borrowed-both'), 'borrowed-both', 0, None, True, True)['state'], 'UNVERIFIED')
        self.assertEqual(probe.assess(clean('borrowed-both') + '\npipeline-cache-device-match',
                                     'borrowed-both', 0, None, True, True)['state'], 'CLEAN_SHUTDOWN_OBSERVED')

    def test_real_immutable_manifest_inputs_and_corrupt_length_header_refusal_before_Vulkan(self):
        exe = Path(os.environ.get('ATLAS_VK_SHUTDOWN_TEST_EXE', 'missing-built-test'))
        self.assertTrue(exe.is_file(), 'newly built fixture required')
        self.assertEqual(probe.sha(exe), os.environ.get('ATLAS_VK_SHUTDOWN_TEST_SHA256', '').lower())
        evidence = os.environ.get('ATLAS_VK_SHUTDOWN_EVIDENCE')
        manifest = os.environ.get('ATLAS_VK_SHUTDOWN_INGEST_MANIFEST')
        self.assertTrue(evidence and manifest, 'explicit real immutable evidence/ingest manifest required')
        inputs = probe.atlas_generator_memory.prepare_inputs(evidence, manifest)
        # These are the preserved original run's known bytes, independent of the helper implementation.
        known = {'program': '1e4e3aa669661cdb', 'map': 'b9b4f36c48aec7ae', 'ingest': 'b7372337063bdd00'}
        for part, value in known.items(): self.assertEqual(inputs['expectedFingerprint'][part], value)
        env = dict(os.environ, ATLAS_VK_TEST_GEN_INGEST=inputs['ingest']['binary'],
                   ATLAS_VK_TEST_GEN_EXPECT_PROGRAM=known['program'], ATLAS_VK_TEST_GEN_EXPECT_MAP=known['map'],
                   ATLAS_VK_TEST_GEN_EXPECT_INGEST=known['ingest'])
        originals = [inputs['files'][name]['path'] for name in ('prog.bin', 'map.bin')] + [inputs['ingest']['binary']]
        before = [probe.sha(path) for path in originals]
        def invoke(expected, message):
            result = subprocess.run([str(exe.resolve()), inputs['dump'], '--gen-shutdown-inputs'], env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=5)
            self.assertEqual(result.returncode, expected, result.stdout)
            self.assertIn(message, result.stdout)
            self.assertNotIn('S3:', result.stdout)
            self.assertNotIn('[vulkan]', result.stdout)
            print(result.stdout)
        invoke(0, 'no device created')
        with tempfile.TemporaryDirectory(prefix='gen-preflight-', dir=probe.ROOT / '.work' / 'codex') as temp:
            negative = Path(temp) / 'ingest.bin'
            data = Path(inputs['ingest']['binary']).read_bytes()
            damaged = bytearray(data); damaged[-1] ^= 1
            negative.write_bytes(damaged)
            env['ATLAS_VK_TEST_GEN_INGEST'] = str(negative)
            invoke(2, 'preflight input FNV differs')
            # Matching a deliberately changed checksum must still not bypass native structural validation.
            negative.write_bytes(data[:-4])
            env['ATLAS_VK_TEST_GEN_EXPECT_INGEST'] = probe.atlas_generator_memory.oracle.fnv_file(negative)
            invoke(2, 'ingest length differs')
            negative.write_bytes(bytes(8) + data[8:])
            env['ATLAS_VK_TEST_GEN_EXPECT_INGEST'] = probe.atlas_generator_memory.oracle.fnv_file(negative)
            invoke(2, 'invalid program/map/ingest header')
        self.assertEqual([probe.sha(path) for path in originals], before)
        print('TRACE native preflight real immutable known program/map/ingest; valid+corrupt+length+header PASS; originals unchanged')

    def test_built_native_preflight_uses_manifest_legacy_FNV_and_rejects_bad_inputs_without_Vulkan(self):
        exe = Path(os.environ.get('ATLAS_VK_SHUTDOWN_TEST_EXE', 'missing-built-test'))
        self.assertTrue(exe.is_file(), 'explicit newly built fixture required for native CPU preflight')
        self.assertEqual(probe.sha(exe), os.environ.get('ATLAS_VK_SHUTDOWN_TEST_SHA256', '').lower())
        with tempfile.TemporaryDirectory(prefix='shutdown-cpu-', dir=probe.ROOT / '.work' / 'codex') as temp:
            root = Path(temp)
            program = [0] * 128; program[0] = 0x4e454741; program[113] = 1
            amap = [0] * 32; amap[0] = 0x50414d41
            ingest = [2, 1, 0, 1, 1, 2, 0x123456, 0x345678, 0, 1]
            for name, words in [('prog.bin', program), ('map.bin', amap), ('ingest.bin', ingest)]:
                (root / name).write_bytes(struct.pack('<' + 'I' * len(words), *words))
            env = dict(os.environ, ATLAS_VK_TEST_GEN_INGEST=str(root / 'ingest.bin'))
            keys = [('prog.bin', 'ATLAS_VK_TEST_GEN_EXPECT_PROGRAM'), ('map.bin', 'ATLAS_VK_TEST_GEN_EXPECT_MAP'),
                    ('ingest.bin', 'ATLAS_VK_TEST_GEN_EXPECT_INGEST')]
            for name, key in keys: env[key] = probe.atlas_generator_memory.oracle.fnv_file(root / name)
            def invoke(expected):
                result = subprocess.run([str(exe.resolve()), str(root), '--gen-shutdown-inputs'], env=env,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=3)
                self.assertEqual(result.returncode, expected, result.stdout)
                self.assertNotIn('S3:', result.stdout)
                self.assertNotIn('[vulkan]', result.stdout)
                return result.stdout
            self.assertIn('no device created', invoke(0))
            # Recreate the original fixture bug: the standard FNV offset must disagree with the saved engine value.
            value = 14695981039346656037
            for byte in (root / 'prog.bin').read_bytes(): value = ((value ^ byte) * 1099511628211) & ((1 << 64) - 1)
            saved = env['ATLAS_VK_TEST_GEN_EXPECT_PROGRAM']
            env['ATLAS_VK_TEST_GEN_EXPECT_PROGRAM'] = f'{value:016x}'
            self.assertIn('preflight input FNV differs', invoke(2))
            env['ATLAS_VK_TEST_GEN_EXPECT_PROGRAM'] = saved
            (root / 'ingest.bin').write_bytes(struct.pack('<II', 0, 0))
            env['ATLAS_VK_TEST_GEN_EXPECT_INGEST'] = probe.atlas_generator_memory.oracle.fnv_file(root / 'ingest.bin')
            self.assertIn('invalid program/map/ingest header', invoke(2))
            (root / 'map.bin').write_bytes(b'bad')
            self.assertIn('input size', invoke(2))

    def test_each_case_has_complete_scoped_receipt(self):
        for case in probe.CASES:
            with self.subTest(case=case):
                self.assertEqual(probe.assess(clean(case), case, 0, None, True)['state'], 'CLEAN_SHUTDOWN_OBSERVED')

    def test_leak_marker_is_red_even_if_native_exit_zero(self):
        result = probe.assess(clean('borrowed-both') + '\ngenerator: grid sections still pending at close; their buffers stay',
                              'borrowed-both', 0, None, True)
        self.assertEqual(result['state'], 'FAILED')
        self.assertTrue(result['leakOrValidationEvidence'])

    def test_owned_device_validation_error_is_red(self):
        self.assertEqual(probe.assess(clean('owned-near') + '\nVUID-vkDestroyDevice-device-05137',
                                     'owned-near', 0, None, True)['state'], 'FAILED')

    def test_actual_validation_failure_is_failed_when_zero_error_marker_is_absent(self):
        text = clean('borrowed-both').replace("validation (the test's device, atlas on it): 0 errors",
                                             "validation (the test's device, atlas on it): 5 errors")
        text += '\nVUID-vkDestroyDevice-device-05137\nFAIL validationErrors == 0'
        result = probe.assess(text, 'borrowed-both', 1, None, True)
        self.assertEqual(result['state'], 'FAILED')
        self.assertTrue(result['missingMarkers'])
        self.assertEqual(probe.assess(text, 'borrowed-both', 1, '60-second bound', True)['state'], 'UNVERIFIED')

    def test_missing_final_device_close_is_unverified(self):
        text = clean('borrowed-far').replace('external-device-destroy-end', '')
        self.assertEqual(probe.assess(text, 'borrowed-far', 0, None, True)['state'], 'UNVERIFIED')

    def test_timeout_and_provenance_changes_cannot_pass(self):
        for reason, unchanged in [('60-second bound', True), (None, False)]:
            self.assertEqual(probe.assess(clean('owned-both'), 'owned-both', 0, reason, unchanged)['state'], 'UNVERIFIED')

    def test_native_failure_or_missing_validation_cannot_pass(self):
        self.assertEqual(probe.assess(clean('owned-near'), 'owned-near', 1, None, True)['state'], 'FAILED')
        text = clean('owned-near').replace('[vulkan] validation on', '[vulkan] validation requested but unavailable')
        self.assertEqual(probe.assess(text, 'owned-near', 0, None, True)['state'], 'UNVERIFIED')

    def test_wrong_executable_hash_never_starts_child(self):
        with tempfile.TemporaryDirectory(prefix='shutdown-cpu-', dir=probe.ROOT / '.work' / 'codex') as temp:
            exe = Path(temp) / 'test.exe'; exe.write_bytes(b'old test')
            with patch.object(probe.subprocess, 'Popen') as launch:
                with self.assertRaises(ValueError):
                    probe.run(types.SimpleNamespace(exe=str(exe), exe_sha256='0' * 64))
                launch.assert_not_called()


if __name__ == '__main__':
    unittest.main()
