"""CPU preflight/ownership regressions. Optional built-tool checks reject input before Vulkan creation."""
import json
import os
from pathlib import Path
import subprocess
import struct
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import atlas_generator_memory as control
import atlas_memory_report as oracle
from test_atlas_memory_report import ingest


def fixture(root, material=11):
    dump = root / 'generator-dump'; dump.mkdir()
    for name in ('prog.bin', 'map.bin', 'feat.bin', 'g2f.bin', 'f2g.bin'):
        (dump / name).write_bytes(bytes([len(name), 0, 0, 0]))
    manifest = ingest(dump, [2, 1, 5, 2, 6, 3, 0xaabbcc, 0x112233, 10, material])
    meta = json.loads(manifest.read_text()); meta['programFnv64'] = oracle.fnv_file(dump / 'prog.bin'); manifest.write_text(json.dumps(meta))
    (root / 'latest.log').write_text("atlas coarse bake: the cache set's key: build 1 program " + meta['programFnv64'] +
        ' ingest ' + meta['fnv64'] + ' map ' + oracle.fnv_file(dump / 'map.bin') + ' extra 5265881b00000003 stages 6 (17) whole 7\n')
    return manifest


def policy(limit=control.CAP_BYTES, fraction=0.1):
    value = int(limit * fraction)
    return {'job': {'available': True, 'membersAvailable': True, 'membersComplete': True, 'namedScope': True,
                    'samplerInJob': True, 'limitBytes': limit, 'peakCommitBytes': value, 'sampledPrivateBytes': value},
            'processes': [{'pid': 1, 'inJob': True, 'privateBytes': value}]}


def complete_events(expected):
    values = [format(expected[k], 'x') if k == 'stageCount' else expected[k] for k in control.PART_NAMES]
    return [{'controlInputs': 'exact generator binaries', 'parts': values},
            {'phase': 'levels', 'tiles': 263, 'made': 263, 'known': 0, 'failed': 0, 'durable': 263, 'queued': 0}]


class GeneratorMemoryTest(unittest.TestCase):
    def test_provenance_hashes_exact_executable_and_source_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            tool = Path(folder) / 'tool.exe'; tool.write_bytes(b'first executable')
            before = control.artifact_provenance(tool)
            self.assertEqual(before['tool']['bytes'], len(b'first executable'))
            self.assertEqual(len(before['tool']['sha256']), 64)
            self.assertIn('native/tools/gen_bake_tool.cpp', before)
            self.assertEqual(control.artifact_provenance(tool), before)
            tool.write_bytes(b'changed executable')
            self.assertNotEqual(control.artifact_provenance(tool), before)

    def test_preflight_and_command_preserve_saved_inputs_and_bounded_scope(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); fixture(root); data = control.prepare_inputs(root)
            self.assertEqual(data['maxMaterialId'], 11)
            self.assertEqual(len(data['files']), 5)
            args = control.command(root / 'tool.exe', data, root / 'output', 150)
            self.assertIn('ingest=' + data['ingest']['binary'], args)
            self.assertIn('expectTiles=263', args); self.assertIn('lod2=0', args)
            self.assertIn('fp=' + str(int('5265881b00000003', 16)), args)
            self.assertIn('seed=1234', args)
            self.assertIn('controlSeconds=150', args)
            self.assertIn('no Minecraft', data['scope'])
            self.assertIn('EMPTY overlay', data['scope'])
            self.assertIn('live overlay recording', data['scope'])

    def test_store_control_requires_both_completed_near_phases(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); fixture(root); data = control.prepare_inputs(root)
            args = control.command(root / 'tool.exe', data, root / 'out', 150, True)
            self.assertIn('mode=storecontrol', args)
            self.assertIn('x=-149.5', args); self.assertIn('z=260.5', args)
            expected = data['expectedFingerprint']; events = complete_events(expected)
            snapshots = [{'phase': p, 'observedWhileChildRunning': True, 'namedChildObserved': True} for p in
                         ('after-feature-destroy', 'after-overlay-destroy', 'after-generator-destroy', 'after-context-destroy')]
            near = [{'phase': name, 'requested': n, 'completed': n, 'nonair_sections': 1,
                     'accepted_sections': 1, 'store_stage_runs': 1, 'rendered_frames': 1} for name, n in (('near441', 441), ('post64', 64))]
            self.assertEqual('COMPLETED_CONTROL', control.assess(events + near, snapshots, expected, 0, None, True)['state'])
            for bad in (near[:1], [near[0], dict(near[1], completed=63)], [near[0], dict(near[1], store_stage_runs=0)]):
                self.assertEqual('UNVERIFIED', control.assess(events + bad, snapshots, expected, 0, None, True)['state'])

    def test_dirty_overlay_requires_stage_output_and_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); fixture(root); data = control.prepare_inputs(root); expected = data['expectedFingerprint']
            self.assertIn('mode=storedirty', control.command(root / 'tool', data, root / 'out', 150, dirty=True))
            snaps = [{'phase': p, 'observedWhileChildRunning': True, 'namedChildObserved': True} for p in
                     ('after-feature-destroy', 'after-overlay-destroy', 'after-generator-destroy', 'after-context-destroy')]
            row = {'phase': 'dirty1', 'requested': 1, 'completed': 1, 'nonair_sections': 1, 'accepted_sections': 1,
                   'store_stage_runs': 1, 'rendered_frames': 1, 'info_fnv64': '0123456789abcdef', 'subs': 'store stage 997: n 1'}
            inputs = complete_events(expected)[:1] + [{'dirtyOverlay': True}, row]
            self.assertEqual('COMPLETED_CONTROL', control.assess(inputs, snaps, expected, 0, None, dirty=True)['state'])
            for bad in (dict(row, completed=0), dict(row, subs=''), dict(row, info_fnv64=''), dict(row, info_fnv64='zzzzzzzzzzzzzzzz')):
                self.assertEqual('UNVERIFIED', control.assess(inputs[:-1] + [bad], snaps, expected, 0, None, dirty=True)['state'])

    def test_owned_watchdog_fires_while_sampling_is_blocked(self):
        import threading
        class Child:
            def __init__(self): self.killed = threading.Event()
            def poll(self): return 1 if self.killed.is_set() else None
            def kill(self): self.killed.set()
        child = Child()
        timer, fired = control.start_owned_watchdog(child, time.monotonic() + 0.05)
        try:
            # Represents a sampler waiting independently of the watchdog thread.
            self.assertTrue(child.killed.wait(1.0))
            self.assertTrue(fired.is_set())
        finally:
            timer.cancel()

    def test_capability_probe_accepts_only_explicit_pre_vulkan_contract(self):
        caps = {'generatorMemoryControl': 1, 'explicitIngestValidation': 1}
        for result in (subprocess.CompletedProcess([], 2, '', 'usage'),
                       subprocess.CompletedProcess([], 0, '{}', ''),
                       subprocess.CompletedProcess([], 0, json.dumps({**caps, 'generatorMemoryControl': True}), ''),
                       subprocess.CompletedProcess([], 0, 'not JSON', '')):
            with self.subTest(result=result), patch.object(control.subprocess, 'run', return_value=result) as probe:
                with self.assertRaises(RuntimeError): control.tool_capabilities('tool.exe', 1)
                self.assertEqual(probe.call_args.args[0], ['tool.exe', '--capabilities'])
        with patch.object(control.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, json.dumps(caps), '')):
            self.assertEqual(control.tool_capabilities('tool.exe', 1), caps)

    def test_bad_saved_inputs_never_launch_child(self):
        for damage in ('ingest', 'layout', 'program', 'map', 'missing-feature', 'oversized-material'):
            with self.subTest(damage=damage), tempfile.TemporaryDirectory() as folder:
                root = Path(folder); manifest = fixture(root, material=65536 if damage == 'oversized-material' else 11)
                if damage == 'ingest': manifest.with_name('ingest.bin').write_bytes(b'bad')
                if damage == 'layout':
                    meta = json.loads(manifest.read_text()); meta['ids']['stride'] = 1; manifest.write_text(json.dumps(meta))
                if damage in ('program', 'map'):
                    (root / 'generator-dump' / ('prog.bin' if damage == 'program' else 'map.bin')).write_bytes(b'abcd')
                if damage == 'missing-feature': (root / 'generator-dump/feat.bin').unlink()
                with patch.object(control.subprocess, 'Popen') as launch:
                    with self.assertRaises((ValueError, OSError)):
                        control.run(root, root / 'tool.exe', sample=lambda *args: policy())
                    launch.assert_not_called()
                self.assertEqual(list(root.glob('generator-memory-control-*')), [])

    def test_job_cap_pressure_and_deadline_refuse_before_launch(self):
        for row in (policy(limit=24 * 2**30), policy(fraction=0.86), {'job': {}}):
            with self.subTest(row=row), self.assertRaises(RuntimeError): control.require_policy(row)
        control.require_policy(policy())
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); fixture(root); tool = root / 'tool.exe'; tool.touch()
            with patch.object(control.subprocess, 'Popen') as launch:
                with self.assertRaises(RuntimeError): control.run(root, tool, sample=lambda *args: policy(fraction=0.86))
                for seconds in (0, 181, float('nan')):
                    with self.assertRaises(ValueError): control.run(root, tool, seconds=seconds)
                launch.assert_not_called()

    def test_incomplete_tiles_timeout_missing_cleanup_and_bad_fingerprint_are_unverified(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); fixture(root); expected = control.prepare_inputs(root)['expectedFingerprint']
            snapshots = [{'phase': phase, 'observedWhileChildRunning': True, 'namedChildObserved': True}
                         for phase in ('after-feature-destroy', 'after-overlay-destroy', 'after-generator-destroy', 'after-context-destroy')]
            events = complete_events(expected)
            self.assertEqual(control.assess(events, snapshots, expected, 0, None)['state'], 'COMPLETED_CONTROL')
            for key, value in [('tiles', 262), ('made', 262), ('failed', 1), ('durable', 262), ('queued', 1)]:
                bad = complete_events(expected); bad[-1][key] = value
                self.assertEqual(control.assess(bad, snapshots, expected, 0, None)['state'], 'UNVERIFIED')
            self.assertEqual(control.assess(events, snapshots, expected, 0, 'deadline')['state'], 'UNVERIFIED')
            self.assertEqual(control.assess(events, [], expected, 0, None)['state'], 'UNVERIFIED')
            self.assertEqual(control.assess(events, snapshots, expected, 1, None)['state'], 'UNVERIFIED')
            # The first real control had only the three feature stages; identical input binaries do not excuse this.
            stages = complete_events(expected); stages[0]['parts'][-1] = '3'
            self.assertEqual(control.assess(stages, snapshots, expected, 0, None)['state'], 'UNVERIFIED')
            for removed in snapshots:
                self.assertEqual(control.assess(events, [s for s in snapshots if s is not removed], expected, 0, None)['state'], 'UNVERIFIED')
            events[0]['parts'][0] = '2'
            self.assertEqual(control.assess(events, snapshots, expected, 0, None)['state'], 'UNVERIFIED')

    def test_stop_targets_only_owned_popen_and_bounds_cleanup(self):
        flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
        args = [sys.executable, '-c', 'import time; time.sleep(30)']
        own = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=flags)
        decoy = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=flags)
        try:
            with tempfile.TemporaryDirectory() as folder:
                self.assertTrue(control.stop_owned(own, Path(folder) / 'cancel', time.monotonic() + 0.5))
            self.assertIsNotNone(own.poll())
            self.assertIsNone(decoy.poll())
        finally:
            for child in (own, decoy):
                if child.poll() is None: child.kill()
                child.wait(timeout=3)

    def test_cancel_drain_observes_cleanup_before_owned_child_exit(self):
        with tempfile.TemporaryDirectory() as folder:
            cancel = Path(folder) / 'cancel'
            script = ('import pathlib,sys,time; p=pathlib.Path(sys.argv[1]); '
                      'exec("while not p.exists(): time.sleep(0.01)"); '
                      'print("after-context-destroy",flush=True); time.sleep(0.1)')
            own = subprocess.Popen([sys.executable, '-c', script, str(cancel)], stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            observed = []
            try:
                def sample_cleanup():
                    observed.append((own.stdout.readline().decode().strip(), own.poll()))
                self.assertTrue(control.stop_owned(own, cancel, time.monotonic() + 5, sample_cleanup))
                self.assertIn(('after-context-destroy', None), observed)
            finally:
                if own.poll() is None: own.kill()
                own.wait(timeout=3); own.stdout.close()


@unittest.skipUnless(os.environ.get('ATLAS_GEN_MEMORY_TEST_TOOL'), 'built-tool CPU negative checks enabled after target build')
class BuiltToolInputTest(unittest.TestCase):
    def test_delayed_durable_drain_and_deadline_use_actual_native_control_loop(self):
        result = subprocess.run([os.environ['ATLAS_GEN_MEMORY_TEST_TOOL'], '--test-control-drain'],
                                capture_output=True, text=True, timeout=3,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), {'delayedCompleted': True, 'delayedPolls': 405,
                         'deadlineCompleted': False, 'deadlinePolls': 200,
                         'legacyCompleted': False, 'legacyPolls': 400})
        self.assertNotIn('controlPhase', result.stdout)

    def test_capabilities_are_available_without_inputs_or_vulkan(self):
        self.assertEqual(control.tool_capabilities(os.environ['ATLAS_GEN_MEMORY_TEST_TOOL'], 3),
                         {'generatorMemoryControl': 1, 'explicitIngestValidation': 1})

    def reject(self, damage):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); manifest = fixture(root, 65536 if damage == 'material' else 11)
            meta = json.loads(manifest.read_text())
            if damage == 'header':
                manifest.with_name('ingest.bin').write_bytes(struct.pack('<II', 0, 0))
                meta['words'] = 2; meta['fnv64'] = oracle.fnv_file(manifest.with_name('ingest.bin'))
            args = [os.environ['ATLAS_GEN_MEMORY_TEST_TOOL'], str(root / 'generator-dump'), str(root / 'unused-cache'),
                    'mode=bake', 'control=memory', 'ingest=' + str(manifest.with_name('ingest.bin')),
                    'ingestWords=' + str(meta['words']), 'ingestFnv=' + ('0' if damage == 'fnv' else meta['fnv64'])]
            result = subprocess.run(args, capture_output=True, text=True, timeout=5,
                                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
            self.assertIn('ingest refused before Vulkan creation', result.stderr)
            self.assertNotIn('controlPhase', result.stdout)
            self.assertFalse((root / 'unused-cache').exists())

    def test_bad_checksum_rejected_before_vulkan(self): self.reject('fnv')
    def test_out_of_domain_material_rejected_before_vulkan(self): self.reject('material')
    def test_empty_states_and_biomes_rejected_before_vulkan(self): self.reject('header')


if __name__ == '__main__': unittest.main()
