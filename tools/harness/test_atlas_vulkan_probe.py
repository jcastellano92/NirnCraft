"""CPU-only checks: no client, launcher, or GPU is used."""
import subprocess
import time
import tempfile
from pathlib import Path
import unittest
import sys
from unittest.mock import patch
import atlas_vulkan_probe as probe
import probe_flags


class FlagTransactionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.cfg = self.root / 'instance.cfg'; self.flags = self.root / 'flags'
        self.evidence = self.root / 'evidence'; self.evidence.mkdir()
        self.cfg.write_bytes(b'JvmArgs="-Xmx4G -Dnirncraft.harness=1 -Dold=yes -Dnirncraft.harness.end=1"\nOther=old\n')
        self.flags.write_bytes(b'old=yes\nunrelated=before\n')
        probe_flags.snapshot(self.cfg, self.flags, self.evidence)
        self.jvm = {'nirncraft.atlas.probeOwner': 'unique', 'nirncraft.atlas.vkSharedSync': 'true'}

    @staticmethod
    def atomic(path, data): Path(path).write_bytes(data)

    def install(self, flags=True):
        probe_flags.journal(self.jvm, self.evidence, self.atomic)
        expected = probe.json.loads((self.evidence / 'flags-expected.json').read_text())
        self.cfg.write_bytes(b'JvmArgs="-Xmx4G ' + expected['marker'].encode() + b'"\nOther=new\n')
        if flags: self.flags.write_bytes(expected['properties'].encode())

    def restore(self, closed=True):
        probe_flags.restore(self.cfg, self.flags, self.evidence, closed, self.atomic)

    def test_success_preserves_unrelated_concurrent_changes(self):
        self.install(); self.flags.write_bytes(self.flags.read_bytes() + b'new.key=kept\n')
        self.restore()
        self.assertIn(b'-Dold=yes', self.cfg.read_bytes()); self.assertIn(b'Other=new', self.cfg.read_bytes())
        self.assertEqual(self.flags.read_bytes(), b'new.key=kept\nold=yes\nunrelated=before\n')

    def test_partial_launch_write_restores_marker(self):
        self.install(flags=False); self.restore()
        self.assertIn(b'-Dold=yes', self.cfg.read_bytes())
        self.assertEqual(self.flags.read_bytes(), b'old=yes\nunrelated=before\n')

    def test_actual_writer_and_journal_include_owned_raw_nmt_only_in_marker(self):
        raw = ('-XX:NativeMemoryTracking=summary',)
        probe_flags.journal(self.jvm, self.evidence, probe.game.lw.atomic, raw)
        expected = probe.json.loads((self.evidence / 'flags-expected.json').read_text())
        with patch.object(probe.game.lw, 'INSTANCE', self.root), patch.object(probe.game, 'FLAGS_FILE', self.flags):
            probe.game._set_group_flags(self.jvm, raw)
        actual = self.cfg.read_bytes()
        self.assertEqual(probe_flags.block(actual), expected['marker'].encode())
        self.assertEqual(actual.count(b'-XX:NativeMemoryTracking=summary'), 1)
        self.assertNotIn(b'NativeMemoryTracking', self.flags.read_bytes())
        self.restore()
        self.assertNotIn(b'NativeMemoryTracking', self.cfg.read_bytes())
        self.assertEqual(self.flags.read_bytes(), b'old=yes\nunrelated=before\n')

    def test_real_writer_partial_failure_restores_original_marker(self):
        raw = ('-XX:NativeMemoryTracking=summary',)
        probe_flags.journal(self.jvm, self.evidence, probe.game.lw.atomic, raw)
        with patch.object(probe.game.lw, 'INSTANCE', self.root), patch.object(probe.game, 'FLAGS_FILE', self.flags), \
             patch.object(probe.game, 'write_flags_file', side_effect=OSError('injected partial write')):
            with self.assertRaisesRegex(OSError, 'partial write'): probe.game._set_group_flags(self.jvm, raw)
        self.assertIn(b'-XX:NativeMemoryTracking=summary', self.cfg.read_bytes())
        self.restore()
        self.assertNotIn(b'NativeMemoryTracking', self.cfg.read_bytes())
        self.assertEqual(self.flags.read_bytes(), b'old=yes\nunrelated=before\n')

    def test_worker_journals_raw_nmt_before_real_writer(self):
        self.jvm.pop('nirncraft.atlas.vkSharedSync')  # the shared worker now exercises the product default
        game_dir = self.root / 'game'; game_dir.mkdir()
        def fail_after_write(group, groups):
            probe.game._set_group_flags(self.jvm)
            raise RuntimeError('injected launch failure after flag write')
        with patch.object(probe.game.lw, 'INSTANCE', self.root), patch.object(probe.game, 'FLAGS_FILE', self.flags), \
             patch.object(probe.game, 'GAME', game_dir), patch.object(probe.game, 'assert_prepared'), \
             patch.object(probe.game, 'ensure', side_effect=fail_after_write), patch.object(probe.game, 'launch'), \
             patch.object(probe.game, '_set_group_flags', wraps=probe.game._set_group_flags), \
             patch.object(probe.atlas_static, 'profile_lines', return_value=[]), \
             patch.dict(probe.os.environ, {'NIRNCRAFT_ATLAS_PROBE_JVM_NMT': '1'}):
            self.assertEqual(probe.worker(self.evidence, time.monotonic() + 10), 2)
        expected = probe.json.loads((self.evidence / 'flags-expected.json').read_text())
        self.assertEqual(probe_flags.block(self.cfg.read_bytes()), expected['marker'].encode())
        self.assertIn(b'-XX:NativeMemoryTracking=summary', self.cfg.read_bytes())
        self.assertNotIn(b'NativeMemoryTracking', self.flags.read_bytes())
        self.restore()
        self.assertNotIn(b'NativeMemoryTracking', self.cfg.read_bytes())

    def test_existing_raw_nmt_outside_marker_is_rejected(self):
        for line in (b'JvmArgs="-XX:NativeMemoryTracking=off -Xmx4G"\n',
                     b'JvmArgs="-Xmx4G -XX:NativeMemoryTracking=summary"\n'):
            self.cfg.write_bytes(line)
            with self.assertRaisesRegex(RuntimeError, 'conflicts'): probe.reject_existing_native_tracking(self.cfg)
        self.cfg.write_bytes(b'JvmArgs="-Xmx4G -Dnirncraft.harness=1 -XX:NativeMemoryTracking=off -Dnirncraft.harness.end=1"\n')
        probe.reject_existing_native_tracking(self.cfg)  # old owned block is replaced and later restored
        with self.assertRaises(ValueError): probe_flags.format_marker({}, ('-XX:Other=1',))

    def test_changed_owned_property_refuses_both_writes(self):
        self.install(); self.flags.write_bytes(self.flags.read_bytes().replace(b'vkSharedSync=true', b'vkSharedSync=false'))
        cfg, flags = self.cfg.read_bytes(), self.flags.read_bytes()
        with self.assertRaisesRegex(RuntimeError, 'property changed'): self.restore()
        self.assertEqual((self.cfg.read_bytes(), self.flags.read_bytes()), (cfg, flags))

    def test_concurrent_change_before_writer_is_not_overwritten(self):
        self.flags.write_bytes(b'old=someone-else\n')
        with self.assertRaisesRegex(RuntimeError, 'before probe write'):
            probe_flags.guard_before_write(self.cfg, self.flags, self.evidence)
        self.assertEqual(self.flags.read_bytes(), b'old=someone-else\n')

    def test_interrupted_restore_can_resume_without_losing_unrelated_changes(self):
        self.install(); self.flags.write_bytes(self.flags.read_bytes() + b'new.key=kept\n')
        def fail_flags(path, data):
            if Path(path) == self.flags: raise OSError('injected flags write failure')
            Path(path).write_bytes(data)
        with self.assertRaisesRegex(OSError, 'injected'):
            probe_flags.restore(self.cfg, self.flags, self.evidence, True, fail_flags)
        self.assertIn(b'-Dold=yes', self.cfg.read_bytes())
        self.restore()
        self.assertIn(b'Other=new', self.cfg.read_bytes())
        self.assertEqual(self.flags.read_bytes(), b'new.key=kept\nold=yes\nunrelated=before\n')

    def test_no_journal_allows_unrelated_config_change(self):
        self.cfg.write_bytes(self.cfg.read_bytes().replace(b'Other=old', b'Other=new'))
        self.restore()
        self.assertIn(b'Other=new', self.cfg.read_bytes())

    def test_diagnostic_windows_path_survives_writer_and_ini_roundtrip(self):
        env = probe.diagnostic_environment({}, r'E:\NirnCraft-scratch\evidence\live-123', True)
        dump = env['NIRNCRAFT_ATLAS_DUMP']
        self.assertEqual(dump, 'E:/NirnCraft-scratch/evidence/live-123/generator-dump')
        self.assertNotIn('\\', dump)
        # Model the observed Qt handling of an unescaped backslash plus following character.
        self.assertNotEqual(probe.re.sub(rb'\\.', b'', b'E:\\NirnCraft-scratch'), b'E:\\NirnCraft-scratch')
        jvm = dict(self.jvm, **{'nirncraft.atlas.dump': dump})
        probe_flags.journal(jvm, self.evidence, probe.game.lw.atomic)
        expected = probe.json.loads((self.evidence / 'flags-expected.json').read_text())
        with patch.object(probe.game.lw, 'INSTANCE', self.root), patch.object(probe.game, 'FLAGS_FILE', self.flags):
            probe.game._set_group_flags(jvm)
        rewritten = probe.re.sub(rb'\\.', b'', self.cfg.read_bytes())
        self.cfg.write_bytes(rewritten)
        self.assertEqual(probe_flags.block(rewritten), expected['marker'].encode())
        self.assertEqual(self.flags.read_text().splitlines().count('nirncraft.atlas.dump=' + dump), 1)
        self.restore()
        self.assertIn(b'-Dold=yes', self.cfg.read_bytes())
        self.assertEqual(self.flags.read_bytes(), b'old=yes\nunrelated=before\n')

    def test_unknown_client_and_changed_marker_do_not_restore(self):
        self.install(); cfg, flags = self.cfg.read_bytes(), self.flags.read_bytes()
        with self.assertRaisesRegex(RuntimeError, 'unverified'): self.restore(False)
        self.assertEqual((self.cfg.read_bytes(), self.flags.read_bytes()), (cfg, flags))
        self.cfg.write_bytes(cfg.replace(b'probeOwner=unique', b'probeOwner=other'))
        with self.assertRaisesRegex(RuntimeError, 'marker changed'): self.restore()

    def test_absent_properties_and_marker_are_removed(self):
        self.cfg.write_bytes(b'JvmArgs="-Xmx4G"\n'); self.flags.unlink()
        probe_flags.snapshot(self.cfg, self.flags, self.evidence)
        # Recreate a clean evidence snapshot to model an originally absent file.
        (self.evidence / 'flags-original-properties.bin').unlink(missing_ok=True)
        self.install(); self.restore()
        self.assertNotIn(b'-Dnirncraft.harness=1', self.cfg.read_bytes())
        self.assertFalse(self.flags.exists())


class SupervisorChecks(unittest.TestCase):
    def test_nmt_environment_is_opt_in_and_rejects_conflicts_or_missing_tool(self):
        with tempfile.TemporaryDirectory() as folder:
            tool = Path(folder) / 'jcmd.exe'; tool.touch()
            original = {'JAVA_TOOL_OPTIONS': '-Xmx2g', 'other': 'untouched'}
            env = probe.native_diagnostic_environment(original, True, tool)
            self.assertEqual(original['JAVA_TOOL_OPTIONS'], '-Xmx2g')
            self.assertEqual(env['JAVA_TOOL_OPTIONS'], '-Xmx2g')
            self.assertEqual(env['NIRNCRAFT_ATLAS_PROBE_JVM_NMT'], '1')
            self.assertEqual(probe.native_diagnostic_environment(original, False, Path('missing')), original)
            with self.assertRaisesRegex(RuntimeError, 'unavailable'):
                probe.native_diagnostic_environment(original, True, Path(folder) / 'missing')
            with self.assertRaisesRegex(RuntimeError, 'JAVA25_HOME'):
                probe.native_diagnostic_environment(original, True, None)
            with self.assertRaisesRegex(RuntimeError, 'conflicts'):
                probe.native_diagnostic_environment({'JAVA_TOOL_OPTIONS': '-XX:NativeMemoryTracking=off'}, True, tool)

    def test_jcmd_owned_identity_disabled_and_timeout(self):
        target = {'pid': 123, 'created': '2099-01-01T00:00:00+00:00', 'exe': 'javaw.exe'}
        sample = {'job': {'available': True, 'membersAvailable': True, 'membersComplete': True,
                          'namedScope': True, 'samplerInJob': True, 'limitBytes': 24 * 2**30,
                          'peakCommitBytes': 1},
                  'processes': [{'pid': 123, 'client': True, 'inJob': True}]}
        class Helper:
            def __init__(self, args, stdout, **kwargs):
                self.returncode = None
                stdout.write(b'Native memory tracking is not enabled\n'); stdout.flush()
            def wait(self, timeout): self.returncode = 0; return 0
            def poll(self): return self.returncode
        with tempfile.TemporaryDirectory() as folder:
            tool = Path(folder) / 'jcmd.exe'; tool.touch()
            with patch.object(probe, 'clients', return_value=[target]), \
                 patch.object(probe.process_memory, 'sample', return_value=sample), \
                 patch.object(probe.subprocess, 'Popen', side_effect=Helper) as launch:
                result = probe.capture_jcmd(target, time.time(), folder, time.monotonic() + 40, 'startup', 'nmt', 5, tool)
                self.assertEqual(result['status'], 'UNVERIFIED')
                self.assertIn('not enabled', result['reason'])
                self.assertEqual(launch.call_args.args[0][2:], ['VM.native_memory', 'summary', 'scale=KB'])
            with patch.object(probe, 'clients', side_effect=[[target], [dict(target, exe='reused.exe')]]), \
                 patch.object(probe.process_memory, 'sample', return_value=sample), \
                 patch.object(probe.subprocess, 'Popen', side_effect=Helper):
                result = probe.capture_jcmd(target, time.time(), folder, time.monotonic() + 40, 'late', 'nmt', 5, tool)
                self.assertEqual(result['status'], 'UNVERIFIED')
                self.assertIn('identity changed', result['reason'])
            for changed in (dict(target, pid=124), dict(target, created='2098-01-01T00:00:00+00:00'),
                            dict(target, exe='different-java.exe')):
                with patch.object(probe, 'clients', return_value=[changed]), patch.object(probe.subprocess, 'Popen') as launch:
                    result = probe.capture_jcmd(target, time.time(), folder, time.monotonic() + 40, 'late', 'nmt', 5, tool)
                    self.assertEqual(result['status'], 'UNVERIFIED'); launch.assert_not_called()
            with patch.object(probe.subprocess, 'Popen') as launch:
                result = probe.capture_jcmd(target, time.time(), folder, time.monotonic() - 1, 'late', 'nmt', 5, tool)
                self.assertEqual(result['status'], 'UNVERIFIED'); launch.assert_not_called()

            class Hung:
                def __init__(self): self.alive = True; self.returncode = None; self.terminated = False
                def wait(self, timeout):
                    if self.alive: raise subprocess.TimeoutExpired('jcmd', timeout)
                    self.returncode = 1; return 1
                def poll(self): return None if self.alive else 1
                def terminate(self): self.terminated = True; self.alive = False
                def kill(self): self.alive = False
            helper = Hung()
            with patch.object(probe, 'clients', return_value=[target]), \
                 patch.object(probe.process_memory, 'sample', return_value=sample), \
                 patch.object(probe.subprocess, 'Popen', return_value=helper):
                result = probe.capture_jcmd(target, time.time(), folder, time.monotonic() + 40, 'shutdown', 'threads', 5, tool)
                self.assertTrue(helper.terminated); self.assertFalse(helper.alive)
                self.assertEqual(result['reason'], 'helper timeout')

    def test_existing_prism_refused_before_any_mutation(self):
        with patch.object(probe.game, 'take_lease'), patch.object(probe.game, 'drop_lease'), \
             patch.object(probe, 'clients', return_value=[]), \
             patch.object(probe, 'launcher_processes', return_value=[{'pid': 8}]), \
             patch.object(probe.game, 'assert_prepared') as prepared, patch.object(probe.subprocess, 'Popen') as launch, \
             patch.object(sys, 'argv', [probe.__file__]):
            with self.assertRaisesRegex(RuntimeError, 'existing Prism'): probe.main()
            prepared.assert_not_called(); launch.assert_not_called()

    def test_launcher_argument_patterns_require_exact_boundaries(self):
        launch, root = probe.launcher_patterns()
        name, directory = probe.game.lw.INSTANCE_NAME, str(probe.game.lw.PRISM_ROOT)
        command = '"PrismLauncher.exe" --dir "' + directory + '" --launch "' + name + '"'
        self.assertIsNotNone(probe.re.search(launch, command)); self.assertIsNotNone(probe.re.search(root, command))
        self.assertIsNone(probe.re.search(launch, command.replace(name, name + '-other')))
        self.assertIsNone(probe.re.search(root, command.replace(directory, directory + '-other')))

    def test_launcher_guard_refuses_missing_reused_or_unowned_launcher(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); ack = root / 'ack.json'; exe = root / 'prismlauncher.exe'
            ack.write_text('{"state":"requested","launcherPid":123}')
            p = {'pid': 123, 'created': '2099-01-01T00:00:00+00:00', 'exe': str(exe), 'launchMatch': True, 'dirMatch': True}
            for entries in ([], [dict(p, pid=124)], [dict(p, exe=str(root / 'other.exe'))],
                            [dict(p, launchMatch=False)], [dict(p, dirMatch=False)]):
                with patch.object(probe, 'launcher_processes', return_value=entries), patch.object(probe, 'launcher_member', return_value=True):
                    with self.assertRaises(RuntimeError): probe.LauncherGuard(ack, time.time(), exe).observe(time.monotonic() + 30)
            with patch.object(probe, 'launcher_processes', return_value=[p]), patch.object(probe, 'launcher_member', return_value=False):
                with self.assertRaisesRegex(RuntimeError, 'unverified'): probe.LauncherGuard(ack, time.time(), exe).observe(time.monotonic() + 30)
            guard = probe.LauncherGuard(ack, time.time(), exe)
            with patch.object(probe, 'launcher_processes', return_value=[p]), patch.object(probe, 'launcher_member', return_value=True):
                guard.observe(time.monotonic() + 30)
            reused = dict(p, created='2098-01-01T00:00:00+00:00')
            with patch.object(probe, 'launcher_processes', return_value=[reused]), patch.object(probe, 'stop_owned_launcher') as stop:
                with self.assertRaisesRegex(RuntimeError, 'ownership changed'): guard.finish(time.monotonic() + 30)
                stop.assert_not_called()

    def test_prevented_ack_allows_restoration_and_late_write_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); evidence = root / 'evidence'; evidence.mkdir()
            instance = root / 'instance'; instance.mkdir(); cfg = instance / 'instance.cfg'
            cfg.write_bytes(b'JvmArgs="-Xmx4G"\n'); flags = root / 'flags'
            options = root / 'options.txt'; options.write_bytes(b'preferredGraphicsBackend:"vulkan"\n')
            probe_flags.snapshot(cfg, flags, evidence)
            ack = evidence / 'ack.json'; ack.write_text('{"state":"prevented"}')
            guard = probe.LauncherGuard(ack, time.time(), root / 'prismlauncher.exe')
            with patch.object(probe.game.lw, 'INSTANCE', instance), patch.object(probe.game, 'FLAGS_FILE', flags), \
                 patch.object(probe, 'launcher_processes', return_value=[]), patch.object(probe.time, 'sleep'):
                self.assertFalse(guard.finish(time.monotonic() + 30))
                result = {}
                probe.restore_after_launcher(guard, options, b'preferredGraphicsBackend:"opengl"\n',
                                             b'preferredGraphicsBackend:"vulkan"\n', evidence, time.monotonic() + 30, result)
                self.assertTrue(result['launcher_absent_before_after_restore'])
                self.assertFalse(flags.exists())
            options.write_bytes(b'preferredGraphicsBackend:"vulkan"\n')
            class LateWriter:
                def __init__(self): self.calls = 0
                def verify_absent(self, deadline, reserve=0):
                    self.calls += 1
                    if self.calls == 2: cfg.write_bytes(b'JvmArgs="late launcher write"\n')
            with patch.object(probe.game.lw, 'INSTANCE', instance), patch.object(probe.game, 'FLAGS_FILE', flags), patch.object(probe.time, 'sleep'):
                result = {}
                with self.assertRaisesRegex(RuntimeError, 'changed after restoration'):
                    probe.restore_after_launcher(LateWriter(), options, b'preferredGraphicsBackend:"opengl"\n',
                                                 b'preferredGraphicsBackend:"vulkan"\n', evidence, time.monotonic() + 30, result)
                self.assertNotIn('launcher_absent_before_after_restore', result)

    def test_failed_launch_ack_without_prism_is_provably_absent(self):
        with tempfile.TemporaryDirectory() as folder:
            ack = Path(folder) / 'ack.json'; ack.write_text('{"state":"failed"}')
            guard = probe.LauncherGuard(ack, time.time(), Path(folder) / 'prismlauncher.exe')
            with patch.object(probe, 'launcher_processes', return_value=[]):
                self.assertFalse(guard.finish(time.monotonic() + 30))

    def test_supervisor_retains_lease_when_launcher_rewrites_after_restore(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder); game_dir = base / 'game'; game_dir.mkdir()
            instance = base / 'instance'; instance.mkdir()
            cfg = instance / 'instance.cfg'; cfg.write_bytes(b'JvmArgs="-Xmx4G"\n')
            flags = game_dir / 'flags'; options = game_dir / 'options.txt'
            options.write_bytes(b'preferredGraphicsBackend:"opengl"\n')
            evidence_root = base / 'evidence'
            class FinishedWorker:
                def poll(self): return 0
            launch_owners = []
            def start_worker(args, env):
                properties = dict(part.split('=', 1) for part in env['NIRNCRAFT_HARNESS_JVM'].split(','))
                launch_owners.append(properties['nirncraft.atlas.probeOwner'])
                Path(env['NIRNCRAFT_HARNESS_LAUNCH_ACK_FILE']).write_text('{"state":"prevented"}')
                return FinishedWorker()
            def closed(deadline, started, seen, ack, env, result, diagnostic=None):
                result.update(client_closed=True, launch_resolved=True); return True
            def late_write(_): cfg.write_bytes(b'JvmArgs="late launcher write"\n')
            with patch.object(probe, 'EVIDENCE_ROOT', evidence_root), patch.object(probe.game, 'GAME', game_dir), \
                 patch.object(probe.game.lw, 'INSTANCE', instance), patch.object(probe.game, 'FLAGS_FILE', flags), \
                 patch.object(probe.game, 'take_lease'), patch.object(probe.game, 'drop_lease') as drop, \
                 patch.object(probe.game, 'assert_prepared'), patch.object(probe.atlas_tools, 'player_files', return_value=True), \
                 patch.object(probe, 'clients', return_value=[]), patch.object(probe, 'launcher_processes', return_value=[]), \
                 patch.object(probe, 'memory_sample', return_value={}), patch.object(probe, 'require_live_memory_policy'), \
                 patch.object(probe, 'record_cache_inventory'), patch.object(probe, 'bounded_snapshot'), \
                 patch.object(probe, 'cleanup_clients', side_effect=closed), patch.object(probe.time, 'sleep', side_effect=late_write), \
                 patch.object(probe.subprocess, 'Popen', side_effect=start_worker), patch.object(sys, 'argv', [probe.__file__]):
                self.assertEqual(probe.main(), 2)
                drop.assert_not_called()
            receipt = probe.json.loads(next(evidence_root.glob('*/supervisor-receipt.json')).read_text())
            self.assertTrue(launch_owners)
            self.assertTrue(all(owner == receipt['probe_owner'] for owner in launch_owners))
            self.assertEqual(str(probe.uuid.UUID(receipt['probe_owner'])), receipt['probe_owner'])
            self.assertIn('changed after restoration', receipt['cleanup_error'])
            self.assertNotIn('launcher_absent_before_after_restore', receipt)

    def test_only_pinned_launcher_may_be_forced_after_owned_close(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); ack = root / 'ack.json'; exe = root / 'prismlauncher.exe'
            ack.write_text('{"state":"requested","launcherPid":123}')
            p = {'pid': 123, 'created': '2099-01-01T00:00:00+00:00', 'exe': str(exe), 'launchMatch': True, 'dirMatch': True}
            guard = probe.LauncherGuard(ack, time.time(), exe)
            with patch.object(probe, 'launcher_processes', return_value=[p]), patch.object(probe, 'launcher_member', return_value=True):
                guard.observe(time.monotonic() + 30)
            with patch.object(probe, 'launcher_processes', side_effect=[[p], [p], []]), \
                 patch.object(probe, 'launcher_member', return_value=True), patch.object(probe.time, 'sleep'), \
                 patch.object(probe, 'stop_owned_launcher') as stop:
                self.assertTrue(guard.finish(time.monotonic() + 30))
                self.assertEqual(probe.identity(stop.call_args.args[0]), probe.identity(p))
            with patch.object(probe, 'launcher_processes') as query, patch.object(probe, 'stop_owned_launcher') as stop:
                with self.assertRaises(TimeoutError): guard.finish(time.monotonic() - 1)
                query.assert_not_called(); stop.assert_not_called()
    def test_fresh_cache_override_is_last_and_parent_unchanged(self):
        with tempfile.TemporaryDirectory() as folder:
            original = {'NIRNCRAFT_HARNESS_JVM': 'other=1,nirncraft.atlas.cacheDir=old'}
            env, root = probe.fresh_cache_environment(original, folder, True)
            self.assertEqual(original['NIRNCRAFT_HARNESS_JVM'], 'other=1,nirncraft.atlas.cacheDir=old')
            properties = dict(item.split('=', 1) for item in env['NIRNCRAFT_HARNESS_JVM'].split(','))
            self.assertEqual(properties['nirncraft.atlas.cacheDir'], root.as_posix())
            self.assertEqual(root.parent, Path(folder).resolve())
            self.assertEqual((root / 'keep-gen-sets').read_text().strip(), '3')

    def test_default_cache_option_does_not_create_or_mutate(self):
        with tempfile.TemporaryDirectory() as folder:
            original = {'NIRNCRAFT_HARNESS_JVM': 'x=1'}
            env, root = probe.fresh_cache_environment(original, folder, False)
            self.assertEqual(env, original); self.assertIsNone(root)
            self.assertEqual(list(Path(folder).iterdir()), [])

    def test_override_inventory_preserves_existing_default_cache(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder); old = base / 'old' / 'seed' / 'gen-keep'; old.mkdir(parents=True)
            (old / 'prior.gch').write_bytes(b'preserve')
            evidence = base / 'evidence'; evidence.mkdir()
            _, root = probe.fresh_cache_environment({}, evidence, True)
            new = root / 'seed' / 'gen-new'; new.mkdir(parents=True); (new / 'tile.gch').write_bytes(b'new')
            probe.record_generator_cache_inventory(evidence, root, 'after')
            info = probe.json.loads((evidence / 'generated-cache-state-after.json').read_text())
            self.assertEqual((info['recordsFiles'], info['bytes'], info['keepGenSets']), (1, 3, '3'))
            self.assertEqual((old / 'prior.gch').read_bytes(), b'preserve')

    def test_fresh_roots_are_unique_and_path_log_requires_exact_match(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder); first = base / 'first'; second = base / 'second'; first.mkdir(); second.mkdir()
            _, a = probe.fresh_cache_environment({}, first, True)
            _, b = probe.fresh_cache_environment({}, second, True)
            self.assertNotEqual(a, b)
            self.assertTrue(probe.cache_path_logged('INFO atlas: the self-baking cache in ' + str(a), a))
            self.assertFalse(probe.cache_path_logged('INFO atlas: the self-baking cache in ' + str(b), a))
            with self.assertRaises(FileExistsError): probe.fresh_cache_environment({}, first, True)

    def test_worker_early_failure_does_not_claim_old_cache_log(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder); game_dir = base / 'game'; (game_dir / 'logs').mkdir(parents=True)
            evidence = base / 'evidence'; evidence.mkdir()
            _, root = probe.fresh_cache_environment({}, evidence, True)
            (game_dir / 'logs/latest.log').write_text('old self-baking cache in ' + str(root) + '\n')
            with patch.object(probe.game, 'GAME', game_dir), \
                 patch.object(probe.game, 'assert_prepared', side_effect=RuntimeError('injected early failure')), \
                 patch.object(probe.game, 'launch'), patch.object(probe.game, '_set_group_flags'), \
                 patch.object(probe.atlas_static, 'profile_lines', return_value=[]), \
                 patch.dict(probe.os.environ, {'NIRNCRAFT_ATLAS_PROBE_CACHE_ROOT': str(root)}):
                self.assertEqual(probe.worker(evidence, time.monotonic() + 5), 2)
            receipt = probe.json.loads((evidence / 'worker-receipt.json').read_text())
            self.assertEqual(receipt['requested_generator_cache_root'], str(root))
            self.assertEqual(receipt['cache_log_basis'], 'unchanged')
            self.assertFalse(receipt['checks']['requested_generator_cache_path_observed'])
            self.assertEqual(list(root.glob('*.gch')), [])

    def test_transport_control_overrides_existing_flag_without_mutating_parent(self):
        original = {'NIRNCRAFT_HARNESS_JVM': 'existing=true,nirncraft.atlas.vkSharedSync=true'}
        host = probe.transport_environment(original, 'host')
        # The real launcher consumes properties in order, with the last value winning.
        properties = dict(item.split('=', 1) for item in host['NIRNCRAFT_HARNESS_JVM'].split(','))
        self.assertEqual(properties['nirncraft.atlas.vkSharedSync'], 'false')
        self.assertEqual(properties['existing'], 'true')
        self.assertEqual(host['NIRNCRAFT_ATLAS_PROBE_TRANSPORT'], 'host')
        self.assertEqual(original, {'NIRNCRAFT_HARNESS_JVM': 'existing=true,nirncraft.atlas.vkSharedSync=true'})
        shared = probe.transport_environment(host, 'shared')
        properties = dict(item.split('=', 1) for item in shared['NIRNCRAFT_HARNESS_JVM'].split(','))
        self.assertNotIn('nirncraft.atlas.vkSharedSync', properties)
        with self.assertRaises(ValueError):
            probe.transport_environment(original, 'unknown')

    def test_diagnostic_inputs_are_opt_in_and_unique_to_probe_evidence(self):
        original = {'NIRNCRAFT_HARNESS_JVM': 'existing=true', 'unrelated': 'preserved'}
        self.assertEqual(probe.diagnostic_environment(original, Path('first'), False), original)
        first = probe.diagnostic_environment(original, Path('first'), True)
        second = probe.diagnostic_environment(original, Path('second'), True)
        self.assertEqual(original, {'NIRNCRAFT_HARNESS_JVM': 'existing=true', 'unrelated': 'preserved'})
        self.assertEqual(first['NIRNCRAFT_HARNESS_JVM'], 'existing=true,nirncraft.atlas.memoryDiagnostics=true')
        self.assertEqual(first['ATLAS_MEMORY_DIAGNOSTICS'], '1')
        self.assertNotEqual(first['NIRNCRAFT_ATLAS_DUMP'], second['NIRNCRAFT_ATLAS_DUMP'])
        self.assertEqual(first['unrelated'], 'preserved')

    def test_initial_memory_pressure_never_launches_or_changes_backend(self):
        limit = 24 * 2**30
        row = {'job': {'available': True, 'membersAvailable': True, 'membersComplete': True, 'namedScope': True, 'samplerInJob': True,
                      'limitBytes': limit, 'peakCommitBytes': int(limit * 0.9)},
               'processes': [{'pid': 1, 'inJob': True, 'privateBytes': int(limit * 0.9)}]}
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); game_dir = root / 'game'; game_dir.mkdir()
            options = game_dir / 'options.txt'
            options.write_bytes(b'preferredGraphicsBackend:"opengl"\r\n')
            before = options.read_bytes()
            with patch.object(probe, 'EVIDENCE_ROOT', root / 'evidence'), \
                 patch.object(probe.game, 'GAME', game_dir), \
                 patch.object(probe.game, 'take_lease'), patch.object(probe.game, 'drop_lease'), \
                 patch.object(probe, 'clients', return_value=[]), \
                 patch.object(probe, 'launcher_processes', return_value=[]), \
                 patch.object(probe.game, 'assert_prepared'), \
                 patch.object(probe.atlas_tools, 'player_files', return_value=True), \
                 patch.object(probe.process_memory, 'sample', return_value=row), \
                 patch.object(probe.subprocess, 'Popen') as launch, \
                 patch.object(sys, 'argv', [probe.__file__]):
                with self.assertRaisesRegex(RuntimeError, 'refusing launch'): probe.main()
                launch.assert_not_called()
            self.assertEqual(options.read_bytes(), before)

    def test_cache_inventory_preserves_versions_and_records_identities(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            cache = root / 'atlas-cache'; cache.mkdir()
            (cache / 'keep-gen-sets').write_text('3\n')
            for name in ('a', 'b'):
                path = cache / 'seed' / ('gen-' + name)
                path.mkdir(parents=True); (path / 'c0.gch').write_bytes(b'cached-world')
            (root / 'mods').mkdir()
            with probe.zipfile.ZipFile(root / 'mods/nirncraft-core.jar', 'w') as jar:
                jar.writestr('nirncraft/generator-identity.txt', 'generator:abc')
                jar.writestr('nirncraft/far-identity.txt', 'far:def')
            before = {p: p.read_bytes() for p in cache.rglob('*') if p.is_file()}
            state = probe.cache_inventory(root)
            self.assertEqual(state['retainedDevelopmentSets'], '3')
            self.assertEqual(state['identity']['generator-identity.txt'], 'generator:abc')
            self.assertEqual([s['name'] for s in state['sets']], ['seed/gen-a', 'seed/gen-b'])
            self.assertEqual([s['bytes'] for s in state['sets']], [12, 12])
            self.assertEqual(before, {p: p.read_bytes() for p in cache.rglob('*') if p.is_file()})

    def test_mislabeled_build_memory_policy_refused_before_live_run(self):
        for row in ({}, {'job': {'limitBytes': 16 * 2**30}}):
            with self.assertRaisesRegex(RuntimeError, 'live job memory policy unverified'):
                probe.require_live_memory_policy(row)
        with self.assertRaisesRegex(RuntimeError, 'accounting incomplete'):
            probe.require_live_memory_policy({'job': {'limitBytes': 24 * 2**30}})
        probe.require_live_memory_policy({'job': {'limitBytes': 24 * 2**30, 'available': True, 'namedScope': True, 'samplerInJob': True,
                'membersAvailable': True, 'membersComplete': True, 'peakCommitBytes': 100},
                'processes': [{'pid': 1, 'inJob': True, 'privateBytes': 100}]})

    def test_memory_samples_are_streamed_with_phase(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(probe.process_memory, 'sample', return_value={'job': {}}):
            probe.memory_sample(folder, phase='before-launch')
            probe.memory_sample(folder, phase='after-client-cleanup')
            rows = [probe.json.loads(line) for line in (Path(folder) / 'memory.jsonl').read_text().splitlines()]
            self.assertEqual([r['phase'] for r in rows], ['before-launch', 'after-client-cleanup'])

    def test_large_client_gets_rate_limited_region_census(self):
        big = {'job': {}, 'processes': [{'pid': 7, 'client': True, 'privateBytes': 9 * 2**30},
                                         {'pid': 8, 'client': False, 'privateBytes': 9 * 2**30}]}
        census = {'pid': 7, 'totals': {}, 'largest': []}
        with tempfile.TemporaryDirectory() as folder, patch.object(probe.process_memory, 'sample', return_value=big), \
                patch.object(probe.process_regions, 'snapshot', return_value=census) as snap, \
                patch.object(probe.time, 'monotonic', side_effect=[100.0, 101.0, 120.0]):
            probe.memory_sample(folder, [7], phase='running')
            probe.memory_sample(folder, [7], phase='running')  # inside the interval: no second census
            probe.memory_sample(folder, [7], phase='running')
            self.assertEqual([7, 7], [call.args[0] for call in snap.call_args_list])
            rows = [probe.json.loads(line) for line in (Path(folder) / 'regions.jsonl').read_text().splitlines()]
            self.assertEqual(2, len(rows))
            self.assertEqual(7, rows[0]['pid'])
        small = {'job': {}, 'processes': [{'pid': 7, 'client': True, 'privateBytes': 2**30}]}
        with tempfile.TemporaryDirectory() as folder, patch.object(probe.process_memory, 'sample', return_value=small), \
                patch.object(probe.process_regions, 'snapshot') as snap:
            probe.memory_sample(folder, [7])
            snap.assert_not_called()
            self.assertFalse((Path(folder) / 'regions.jsonl').exists())
    def test_route_memory_controls_only_change_the_route_spec_when_requested(self):
        base = probe.atlas_tools.route_spec()
        self.assertEqual(10, len(base['captures']))
        self.assertIn('window', base)
        with patch.dict(probe.os.environ, {'NIRNCRAFT_ATLAS_ROUTE_CONTROL': 'no-captures'}):
            spec = probe.atlas_tools.route_spec()
            self.assertEqual([], spec['captures'])
            self.assertEqual(base['window'], spec['window'])
        with patch.dict(probe.os.environ, {'NIRNCRAFT_ATLAS_ROUTE_CONTROL': 'no-captures,keep-window'}):
            spec = probe.atlas_tools.route_spec()
            self.assertEqual([], spec['captures'])
            self.assertNotIn('window', spec)
        with patch.dict(probe.os.environ, {'NIRNCRAFT_ATLAS_ROUTE_CONTROL': 'bogus'}):
            with self.assertRaises(ValueError):
                probe.atlas_tools.route_spec()

    def test_route_control_cli_is_memory_attribution_only(self):
        env = probe.route_environment({}, True, control='no-captures')
        self.assertEqual('no-captures', env['NIRNCRAFT_ATLAS_ROUTE_CONTROL'])
        self.assertNotIn('NIRNCRAFT_ATLAS_ROUTE_CONTROL', probe.route_environment({}, True))
        self.assertNotIn('NIRNCRAFT_ATLAS_ROUTE_CONTROL',
                         probe.route_environment({'NIRNCRAFT_ATLAS_ROUTE_CONTROL': 'x'}, True))
    def test_exhausted_snapshot_budget_does_not_spawn_helper(self):
        with patch.object(probe.subprocess, 'Popen') as start:
            with self.assertRaises(TimeoutError):
                probe.bounded_snapshot(Path('unused'), time.monotonic() - 1, {})
            start.assert_not_called()


    def test_late_client_hung_close_forced_cleanup(self):
        p = {'pid': 123, 'created': '2099-01-01T00:00:00+00:00', 'exe': 'javaw.exe'}
        class HungClose:
            def __init__(self): self.alive, self.timeouts = True, []
            def poll(self): return None if self.alive else 0
            def terminate(self): self.alive = False
            def wait(self, timeout):
                self.timeouts.append(timeout)
                if self.alive: raise subprocess.TimeoutExpired('close', timeout)
        close = HungClose()
        with tempfile.TemporaryDirectory() as d:
            ack = Path(d) / 'ack.json'; ack.write_text('{"state":"requested"}')
            seen, result = {}, {}
            with patch.object(probe, 'clients', side_effect=[[], [], [p], [p], [], []]), \
                 patch.object(probe.subprocess, 'Popen', return_value=close), \
                 patch.object(probe, 'stop_owned') as stop, patch.object(probe.time, 'sleep'):
                self.assertTrue(probe.cleanup_clients(time.monotonic() + 50, time.time(), seen, ack, {}, result))
            stop.assert_called_once()
            # Cleanup gets a real bounded drain opportunity, rather than the former
            # 15-second force, while still reserving time to revalidate and snapshot.
            # stop_helper waits again only after the close helper was terminated.
            self.assertGreater(close.timeouts[0], 25)
            self.assertLessEqual(close.timeouts[0], 30)
            self.assertTrue(result['client_closed'])
            self.assertTrue(result['forced_cleanup'])
            self.assertEqual(probe.identity(p), probe.identity(stop.call_args.args[0]))

    def test_shutdown_diagnostic_precedes_owned_force_without_extending_close(self):
        p = {'pid': 123, 'created': '2099-01-01T00:00:00+00:00', 'exe': 'javaw.exe'}
        order = []
        class HungClose:
            def __init__(self): self.alive = True
            def poll(self): return None if self.alive else 0
            def terminate(self): self.alive = False
            def wait(self, timeout):
                if self.alive: raise subprocess.TimeoutExpired('close', timeout)
                return 0
        with tempfile.TemporaryDirectory() as d:
            ack = Path(d) / 'ack.json'; ack.write_text('{"state":"requested"}')
            with patch.object(probe, 'clients', side_effect=[[p], [p], [], []]), \
                 patch.object(probe.subprocess, 'Popen', return_value=HungClose()), \
                 patch.object(probe, 'stop_owned', side_effect=lambda *a: order.append('force')), \
                 patch.object(probe.time, 'sleep'):
                result = {}
                self.assertTrue(probe.cleanup_clients(time.monotonic() + 50, time.time(), {}, ack, {}, result,
                                                       lambda *a: order.append('thread-dump')))
            self.assertEqual(order, ['thread-dump', 'force'])
            self.assertTrue(result['forced_cleanup'])

    def test_changed_identity_refuses_termination(self):
        p = {'pid': 123, 'created': '2099-01-01T00:00:00+00:00', 'exe': 'javaw.exe'}
        changed = dict(p, exe='other-java.exe')
        class Close:
            def wait(self, timeout): pass
        with tempfile.TemporaryDirectory() as d:
            ack = Path(d) / 'ack.json'; ack.write_text('{"state":"requested"}')
            with patch.object(probe, 'clients', side_effect=[[p], [changed]]), \
                 patch.object(probe.subprocess, 'Popen', return_value=Close()), \
                 patch.object(probe, 'stop_owned') as stop:
                with self.assertRaisesRegex(RuntimeError, 'identity changed'):
                    probe.cleanup_clients(time.monotonic() + 50, time.time(), {}, ack, {}, {})
                stop.assert_not_called()

    def test_slow_cim_aborts_cleanup_without_stop(self):
        with patch.object(probe, 'clients', side_effect=subprocess.TimeoutExpired('cim', 1)), \
             patch.object(probe, 'stop_owned') as stop:
            with self.assertRaises(subprocess.TimeoutExpired):
                probe.cleanup_clients(time.monotonic() + 50, time.time(), {}, Path('missing'), {}, {})
            stop.assert_not_called()

    def test_hung_snapshot_stops_only_helper(self):
        class Hung:
            def __init__(self): self.alive = True
            def poll(self): return None if self.alive else 0
            def terminate(self): self.alive = False
            def wait(self, timeout):
                if self.alive: raise subprocess.TimeoutExpired('snapshot', timeout)
        helper, result = Hung(), {}
        with patch.object(probe.subprocess, 'Popen', return_value=helper):
            probe.bounded_snapshot(Path('unused'), time.monotonic() + 10, result)
        self.assertFalse(helper.alive)
        self.assertTrue(result['snapshot_timeout'])

    def test_launch_handshake_requires_proof(self):
        with tempfile.TemporaryDirectory() as d:
            ack = Path(d) / 'ack.json'
            self.assertFalse(probe.launch_resolved(ack, {}, []))
            ack.write_text('{"state":"requested"}')
            self.assertFalse(probe.launch_resolved(ack, {}, []))
            self.assertTrue(probe.launch_resolved(ack, {1: 1}, []))
            self.assertFalse(probe.launch_resolved(ack, {1: 1}, [1]))
            ack.write_text('{"state":"prevented"}')
            self.assertTrue(probe.launch_resolved(ack, {}, []))

    def test_budget_exhaustion_never_rounds_up(self):
        with self.assertRaises(TimeoutError):
            probe.budget(time.monotonic() + 0.01, 5, reserve=1)
        self.assertLessEqual(probe.budget(time.monotonic() + 10, 2), 2)

    def test_fresh_client_identity(self):
        now = time.time()
        fresh = {'pid': 123, 'created': '2099-01-01T00:00:00+00:00', 'exe': 'javaw.exe'}
        self.assertTrue(probe.fresh_identity(fresh, now))
        self.assertFalse(probe.fresh_identity(dict(fresh, created='2000-01-01T00:00:00+00:00'), now))
        self.assertFalse(probe.fresh_identity(dict(fresh, exe=None), now))
        self.assertNotEqual(probe.identity(fresh), probe.identity(dict(fresh, created='2000-01-01T00:00:00+00:00')))

    def test_force_stop_checks_identity_before_stop(self):
        ident = {'pid': 123, 'created': '2099-01-01T00:00:00+00:00', 'exe': 'javaw.exe'}
        with patch.object(probe.subprocess, 'run') as run:
            probe.stop_owned(ident, timeout=0.2)
        args, kwargs = run.call_args
        script = args[0][-1]
        self.assertLess(script.index('CreationDate'), script.index('Stop-Process'))
        self.assertIn('ExecutablePath', script)
        self.assertIn('CommandLine', script)
        self.assertEqual(kwargs['timeout'], 0.2)
        self.assertIs(kwargs['stdout'], subprocess.DEVNULL)

    def test_helper_only_terminated_if_alive(self):
        class Helper:
            def __init__(self): self.stopped = False
            def poll(self): return None if not self.stopped else 0
            def terminate(self): self.stopped = True
            def wait(self, timeout): self.timeout = timeout
        helper = Helper()
        probe.stop_helper(helper, time.monotonic() + 2)
        self.assertTrue(helper.stopped)
        self.assertGreater(helper.timeout, 0)

    def test_cancel_marker_precedes_worker_termination(self):
        with tempfile.TemporaryDirectory() as d:
            marker = Path(d) / 'cancel'
            class Helper:
                def poll(self): return None
                def terminate(self): self.assert_marker = marker.exists()
                def wait(self, timeout): pass
            helper = Helper()
            probe.cancel_and_stop(marker, helper, time.monotonic() + 2)
            self.assertTrue(helper.assert_marker)


class GlTransportTests(unittest.TestCase):
    def test_gl_environment_does_not_enable_shared_mode(self):
        original = {'NIRNCRAFT_HARNESS_JVM': 'example=value'}
        result = probe.transport_environment(original, 'gl')
        self.assertEqual(original, {'NIRNCRAFT_HARNESS_JVM': 'example=value'})
        self.assertEqual(result['NIRNCRAFT_ATLAS_PROBE_TRANSPORT'], 'gl')
        self.assertNotIn('vkSharedSync', result['NIRNCRAFT_HARNESS_JVM'])

    def test_gl_evidence_requires_latest_on_and_positive_gpu_profile(self):
        active = '[27Oct2026 03:39:48.229] [Render thread/INFO] [com.nirncraft.NirnCraft/]: Using graphics backend OpenGL\n[27Oct2026 03:39:48.229] [Render thread/INFO] [com.nirncraft.NirnCraft/]: Infinite ATLAS far field on (raster columns): atlas_core 0.1.0 (abi 0.17.0; slang far kernel)'
        gpu = 'frame{atlasMs=1.25 unified=true} ready{ok} quality{tier 1} core={"interop":"host-copy","reach":256,"pipelinesBuilding":0}'
        self.assertTrue(probe.gl_ready_gpu_profile_observed(active, gpu))
        self.assertTrue(probe.abi_logged(active, '0.17.0'))
        self.assertFalse(probe.gl_ready_gpu_profile_observed(active + '\nNirnCraft: Infinite ATLAS far field off', gpu))
        self.assertFalse(probe.gl_ready_gpu_profile_observed(active + '\nInfinite ATLAS stopped', gpu))
        self.assertFalse(probe.gl_ready_gpu_profile_observed(active, gpu.replace('atlasMs=1.25', 'atlasMs=0')))
        self.assertFalse(probe.gl_ready_gpu_profile_observed(active, gpu.replace('atlasMs=1.25', 'missing=1.25')))
        self.assertFalse(probe.gl_ready_gpu_profile_observed(active, gpu.replace('host-copy', 'cpu (worker)')))
        self.assertFalse(probe.gl_ready_gpu_profile_observed('', gpu))
        self.assertFalse(probe.abi_logged(active, '0.18.0'))


class PreparedNativeTests(unittest.TestCase):
    def test_missing_build_accepts_only_fresh_packaged_native(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); built = root / 'built.dll'; shipped = root / 'shipped.dll'
            shipped.write_bytes(b'current')
            sources = SimpleNamespace(stale=lambda platform, digest: False)
            with patch.object(probe.game, 'NATIVE_BUILT', built), patch.object(probe.game, 'NATIVE_SHIPPED', shipped):
                self.assertTrue(probe.game.prepared_native('digest', sources))
                sources.stale = lambda platform, digest: True
                self.assertFalse(probe.game.prepared_native('digest', sources))
                sources.stale = lambda platform, digest: False
                built.write_bytes(b'different')
                with patch.object(probe.game, 'native_stale', return_value=False):
                    self.assertFalse(probe.game.prepared_native('digest', sources))


class ResourceReloadTests(unittest.TestCase):
    def test_reload_environment_is_explicit_gl_only_and_clears_inherited_request(self):
        old = {'NIRNCRAFT_ATLAS_PROBE_RESOURCE_RELOAD': '1'}
        self.assertNotIn('NIRNCRAFT_ATLAS_PROBE_RESOURCE_RELOAD', probe.reload_environment(old, 'gl', False))
        self.assertEqual(old['NIRNCRAFT_ATLAS_PROBE_RESOURCE_RELOAD'], '1')
        self.assertEqual(probe.reload_environment({}, 'gl', True)['NIRNCRAFT_ATLAS_PROBE_RESOURCE_RELOAD'], '1')
        with self.assertRaises(ValueError): probe.reload_environment({}, 'shared', True)

    def test_ready_after_reload_requires_new_context_ingest_and_complete_key(self):
        boot = 'Using graphics backend OpenGL\nInfinite ATLAS far field on'
        key = "atlas coarse bake: the cache set's key: build cac47eaf36f7a839 program 1e4e3aa669661cdb ingest b7372337063bdd00 map b9b4f36c48aec7ae extra de033c0000000003 stages c2e892498a31c612 (17) whole 1e5dc6eb8bdd05c3"
        fresh = 'Infinite ATLAS far field on\natlas: ingest components full=b7372337063bdd00\n' + key
        profile = 'frame{atlasMs=1.25 unified=true} ready{ok} quality{tier 1} core={"interop":"host-copy","reach":256,"pipelinesBuilding":0,"genCache":{"fingerprint":"1e5dc6eb8bdd05c3"}}'
        self.assertTrue(probe.recreated_gl_ready(fresh, boot, profile))
        self.assertFalse(probe.recreated_gl_ready('', boot, profile))
        self.assertFalse(probe.recreated_gl_ready(key, boot, profile))
        self.assertFalse(probe.recreated_gl_ready(fresh.replace('atlas: ingest components ', 'other '), boot, profile))
        self.assertFalse(probe.recreated_gl_ready(fresh.replace(' stages c2e892498a31c612 (17)', ''), boot, profile))
        self.assertFalse(probe.recreated_gl_ready(fresh, boot + '\nInfinite ATLAS stopped', profile))
        self.assertFalse(probe.recreated_gl_ready(fresh, boot, profile.replace('ready{ok}', 'ready{pending: generator not running}')))
        self.assertFalse(probe.recreated_gl_ready(fresh, boot, profile.replace('"fingerprint":"1e5dc6eb8bdd05c3"', '"fingerprint":"0000000000000000"')))

    def test_reload_requires_profile_after_new_context_markers_even_with_identical_key(self):
        boot = 'Using graphics backend OpenGL\nInfinite ATLAS far field on\n'
        fresh = "Infinite ATLAS far field on\natlas: ingest components full=b7372337063bdd00\natlas coarse bake: the cache set's key: build cac47eaf36f7a839 program 1e4e3aa669661cdb ingest b7372337063bdd00 map b9b4f36c48aec7ae extra de033c0000000003 stages c2e892498a31c612 (17) whole 1e5dc6eb8bdd05c3\n"
        ready = 'frame{atlasMs=1.25 unified=true} ready{ok} quality{tier 1} core={"interop":"host-copy","reach":256,"pipelinesBuilding":0,"genCache":{"fingerprint":"1e5dc6eb8bdd05c3"}}\n'
        with tempfile.TemporaryDirectory() as d:
            latest, profile = Path(d) / 'latest.log', Path(d) / 'profile.log'
            for later_profile in (False, True):
                latest.write_text(boot); profile.write_text(ready)
                clock = {'now': 0}
                def resources(*args, **kwargs): latest.write_text(boot + fresh)
                def advance(seconds):
                    clock['now'] += 60
                    if later_profile:
                        with profile.open('a') as stream: stream.write(ready)
                with (patch.object(probe.game, 'actions', side_effect=resources),
                      patch.object(probe.time, 'monotonic', side_effect=lambda: clock['now']),
                      patch.object(probe.time, 'sleep', side_effect=advance)):
                    if later_profile:
                        observed = probe.reload_gl_resources(profile, latest, 100)
                        self.assertTrue(observed['fresh_context_and_ingest_observed'])
                    else:
                        with self.assertRaises(RuntimeError): probe.reload_gl_resources(profile, latest, 100)


class GlReceiptTests(unittest.TestCase):
    def test_saved_coarse_bake_log_extracts_real_parts_and_missing_is_unknown(self):
        line = "[27Oct2026 08:10:13.545] [atlas coarse bake/INFO] [com.nirncraft.NirnCraft/]: atlas coarse bake: the cache set's key: build cac47eaf36f7a839 program 1e4e3aa669661cdb ingest b7372337063bdd00 map b9b4f36c48aec7ae extra de033c0000000003 stages c2e892498a31c612 (17) whole 1e5dc6eb8bdd05c3"
        parts = probe.fingerprint_observations(line)[0]['parts']
        self.assertEqual(parts['ingest'], 'b7372337063bdd00')
        self.assertEqual(parts['whole'], '1e5dc6eb8bdd05c3')
        self.assertEqual(parts['stage_count'], 17)
        self.assertIsNone(probe.fingerprint_observations("atlas coarse bake: the cache set's key: build cac47eaf36f7a839")[0]['parts']['ingest'])
        self.assertEqual(probe.fingerprint_observations('unrelated'), [])

    def test_readiness_latch_survives_later_negative_sample(self):
        result = {'checks': {}}
        probe.latch_ready(result, 'ready_gpu_profile_observed', 'ready{ok} atlasMs=1.25', 124.1)
        result['checks']['latest_ready_gpu_profile_observed'] = False
        self.assertTrue(result['checks']['ready_gpu_profile_observed'])
        self.assertEqual(result['readiness_observation']['profile'], 'ready{ok} atlasMs=1.25')
        self.assertFalse(result['checks']['latest_ready_gpu_profile_observed'])

    def test_pose_requires_existing_player_and_backs_up_before_change(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); evidence = root / 'evidence'; evidence.mkdir()
            with self.assertRaises(RuntimeError): probe.prepare_gl_pose([], evidence)
            player = root / 'player.dat'; player.write_bytes(b'old-data')
            def update(path, *pose):
                self.assertEqual((evidence / 'player-before/0.dat').read_bytes(), b'old-data')
                self.assertEqual(probe.json.loads((evidence / 'player-pose.json').read_text())['files'][0]['state'], 'BACKED_UP')
                Path(path).write_bytes(b'new-data')
                return (1.0, 2.0, 3.0)
            with patch.object(probe.atlas_tools, 'set_player_pose', side_effect=update):
                poses = probe.prepare_gl_pose([player], evidence)
            self.assertEqual(poses[0]['old_position'], [1.0, 2.0, 3.0])
            self.assertEqual(player.read_bytes(), b'new-data')
            self.assertTrue((evidence / 'player-pose.json').exists())


class SavedPoseNbtTests(unittest.TestCase):
    @staticmethod
    def player(rotation=True, wrong_rotation=False):
        import gzip, struct
        tag = lambda name, kind, count, data: b'\x09' + struct.pack('>H', len(name)) + name.encode() + bytes([kind]) + struct.pack('>i', count) + data
        body = tag('Pos', 6, 3, struct.pack('>ddd', 10, 20, 30))
        if rotation:
            body += tag('Rotation', 6 if wrong_rotation else 5, 2,
                        struct.pack('>dd', 1, 2) if wrong_rotation else struct.pack('>ff', 1, 2))
        return gzip.compress(b'\x0a\x00\x00' + body + b'\x00')

    def test_valid_nbt_roundtrips_both_position_and_rotation(self):
        import gzip, struct
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'player.dat'; p.write_bytes(self.player())
            old = probe.atlas_tools.set_player_pose(p, -149.5, 200, 260.5, -90, 0)
            raw = gzip.decompress(p.read_bytes())
            self.assertEqual(old, (10, 20, 30))
            self.assertIn(struct.pack('>ddd', -149.5, 200, 260.5), raw)
            self.assertIn(struct.pack('>ff', -90, 0), raw)

    def test_missing_or_malformed_rotation_leaves_original_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            for name, raw in [('missing', self.player(False)), ('wrong', self.player(True, True))]:
                p = Path(d) / name; p.write_bytes(raw)
                with self.assertRaises(ValueError): probe.atlas_tools.set_player_pose(p, 1, 2, 3, 4, 5)
                self.assertEqual(p.read_bytes(), raw)

    def test_partial_failure_leaves_backups_and_incomplete_manifest(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); evidence = root / 'evidence'; evidence.mkdir()
            first = root / 'a.dat'; second = root / 'b.dat'
            first.write_bytes(self.player()); bad = self.player(False); second.write_bytes(bad)
            with self.assertRaises(ValueError): probe.prepare_gl_pose([first, second], evidence)
            rec = probe.json.loads((evidence / 'player-pose.json').read_text())
            self.assertEqual(rec['state'], 'INCOMPLETE')
            self.assertEqual([x['state'] for x in rec['files']], ['UPDATED_AND_VERIFIED', 'BACKED_UP'])
            self.assertEqual((evidence / 'player-before/1.dat').read_bytes(), bad)
            self.assertEqual(second.read_bytes(), bad)


class RouteComparisonTests(unittest.TestCase):
    def test_timing_mode_is_opt_in_and_rejects_diagnostics_or_max_quality(self):
        original = {'keep': 'yes', 'NIRNCRAFT_ATLAS_PROBE_ROUTE': '1'}
        self.assertNotIn('NIRNCRAFT_ATLAS_PROBE_ROUTE', probe.route_environment(original, False))
        self.assertEqual(original['NIRNCRAFT_ATLAS_PROBE_ROUTE'], '1')
        self.assertEqual(probe.route_environment({}, True)['NIRNCRAFT_ATLAS_PROBE_ROUTE'], '1')
        for env in ({'VK_INSTANCE_LAYERS': 'VK_LAYER_KHRONOS_validation'}, {'ATLAS_MEMORY_DIAGNOSTICS': '1'},
                    {'NIRNCRAFT_HARNESS_JVM': 'nirncraft.autotest=true'},
                    {'NIRNCRAFT_HARNESS_JVM': 'nirncraft.quality.adaptive=false'},
                    {'JAVA_TOOL_OPTIONS': '-XX:NativeMemoryTracking=summary'},
                    {'NIRNCRAFT_HARNESS_JVM': 'nirncraft.atlas.memoryDiagnostics=true'}):
            with self.subTest(env=env), self.assertRaises(RuntimeError): probe.route_environment(env, True)

    def test_route_control_may_add_heap_diagnostics_without_input_dump(self):
        with self.assertRaises(RuntimeError): probe.require_route_flags({'nirncraft.atlas.memoryDiagnostics': 'true'})
        probe.require_route_flags({'nirncraft.atlas.memoryDiagnostics': 'true'}, attribution=True)
        with self.assertRaises(RuntimeError): probe.require_route_flags({'nirncraft.atlas.dump': 'x'}, attribution=True)
        env = probe.diagnostic_environment({}, 'ev', True, dump=False)
        self.assertEqual('1', env['ATLAS_MEMORY_DIAGNOSTICS'])
        self.assertNotIn('NIRNCRAFT_ATLAS_DUMP', env)
        self.assertIn('NIRNCRAFT_ATLAS_DUMP', probe.diagnostic_environment({}, 'ev', True))
        args = ['--route-comparison', '--route-control', 'no-captures', '--memory-diagnostics', '--limit', '299']
        with patch.object(sys, 'argv', [probe.__file__, *args]), \
                patch.object(probe.game, 'take_lease', side_effect=KeyError('reached')):
            with self.assertRaises(KeyError): probe.main()

    def test_cli_does_not_extend_ordinary_probe_or_allow_route_cache_reset(self):
        for args in (['--limit', '299'], ['--route-comparison', '--fresh-generator-cache'],
                     ['--route-comparison', '--memory-diagnostics'], ['--route-comparison', '--transport', 'host']):
            with patch.object(sys, 'argv', [probe.__file__, *args]), patch.object(probe.game, 'take_lease') as lease:
                with self.assertRaises(SystemExit): probe.main()
                lease.assert_not_called()
        with patch.object(sys, 'argv', [probe.__file__, '--close-worker']), patch.object(probe.game, 'close') as close:
            self.assertEqual(probe.main(), 0)
            close.assert_called_once()

    def test_insufficient_remaining_time_writes_refusal_without_starting_route(self):
        with tempfile.TemporaryDirectory() as d, patch.object(probe.atlas_tools, 'fly_route') as fly:
            row = probe.route_comparison(d, time.monotonic() + 124)
            fly.assert_not_called()
            self.assertEqual('UNVERIFIED', row['state'])
            self.assertIn('125 work seconds', row['error'])
            self.assertEqual(row, probe.json.loads((Path(d) / 'route/receipt.json').read_text()))

    def test_real_route_action_failure_does_not_recover_or_relaunch(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); shots = root / 'shots'; shots.mkdir()
            with patch.object(probe.game, 'GAME', root), patch.object(probe.game, 'SHOTS', shots), \
                 patch.object(probe.game, 'ensure_visible'), patch.object(probe.game, 'log_mark', return_value=0), \
                 patch.object(probe.atlas_tools, 'wait_alive', return_value=0), \
                 patch.object(probe.game.lw, 'processes', return_value=[]), \
                 patch.object(probe.game, 'actions', side_effect=RuntimeError('deliberate route action failure')) as actions, \
                 patch.object(probe.game, 'recover') as recover, patch.object(probe.game, 'launch') as launch:
                row = probe.route_comparison(root, time.monotonic() + 140)
            self.assertEqual('UNVERIFIED', row['state'])
            self.assertIn('deliberate route action failure', row['error'])
            recover.assert_not_called(); launch.assert_not_called()
            self.assertLessEqual(actions.call_args.kwargs['timeout'], 130)
            self.assertEqual(row, probe.json.loads((root / 'route/receipt.json').read_text()))

    @staticmethod
    def raw_route(root, count=10):
        shots = []
        for i in range(count):
            shot = root / f'capture{i}.png'; shot.write_bytes(b'captured fixture bytes'); shots.append(str(shot))
        return {'ticks': 1840, 'display': {'framebufferW': 2560, 'framebufferH': 1440},
                'load': {'ready': True}, 'shots': shots, 'captures': [{} for _ in shots],
                'total': {'frame': {'n': 5000, 'p99': 55.0, 'max': 100.0}}}

    def test_completed_route_preserves_standard_failures_and_reports_relative_observation(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); raw = self.raw_route(root)
            metrics = {'absentMetrics': [], 'frames': 5000, 'p99': 55.0, 'max': 100.0}
            fails = ['p99 frame 55 > 20', 'max frame 100 > 50']
            with patch.object(probe.atlas_tools, 'fly_route', return_value=raw), \
                 patch.object(probe.atlas_tools, 'summarize', return_value=(metrics, fails)):
                row = probe.route_comparison(root, time.monotonic() + 140)
            self.assertEqual('OBSERVED_ROUTE_COMPARISON', row['state'])
            self.assertEqual(fails, row['standardGateFails'])
            self.assertEqual({'p99': 20.0, 'max': 50.0}, {k: row['standardBar'][k] for k in ('p99', 'max')})
            self.assertFalse(row['relative']['p99AtOrBelowHistorical'])
            self.assertFalse(row['relative']['maxAtOrBelowHistorical'])
            self.assertEqual(10, len(row['screenshots']))
            self.assertEqual(raw, probe.json.loads((root / 'route/raw-report.json').read_text()))
            self.assertEqual(row, probe.json.loads((root / 'route/receipt.json').read_text()))
            self.assertTrue(all((root / 'route' / Path(p).name).exists() for p in raw['shots']))

    def test_missing_capture_or_invalid_metric_cannot_be_a_complete_observation(self):
        for failure in ('capture', 'metric', 'resolution', 'loading'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as d:
                root = Path(d); raw = self.raw_route(root, 9 if failure == 'capture' else 10)
                if failure == 'metric': raw['total']['frame']['p99'] = float('nan')
                if failure == 'resolution': raw['display']['framebufferW'] = 1280
                if failure == 'loading': raw['load']['ready'] = False
                with patch.object(probe.atlas_tools, 'fly_route', return_value=raw), \
                     patch.object(probe.atlas_tools, 'summarize', return_value=({'absentMetrics': []}, [])):
                    row = probe.route_comparison(root, time.monotonic() + 140)
                self.assertEqual('UNVERIFIED', row['state'])
                self.assertNotIn('relative', row)


if __name__ == '__main__': unittest.main()
