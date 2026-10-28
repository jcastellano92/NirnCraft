"""Unit tests for parity_capture.py launcher config sanitization and restore."""
import tempfile
import unittest
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import parity_capture


class ParityCaptureConfigTest(unittest.TestCase):
    def test_sanitize_instance_cfg_strips_stray_duplicates_and_parity_overrides(self):
        dirty = (
            b'[General]\r\n'
            b'JvmArgs="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5006 '
            b'-XX:+UseG1GC -XX:MaxGCPauseMillis=200 '
            b'-Dnirncraft.atlas.clientGen=true -Dnirncraft.atlas.profile=true '
            b'-Dnirncraft.atlas.safeMode=false -Dnirncraft.atlas.unified=true '
            b'-Dnirncraft.harness=1 '
            b'-Dnirncraft.atlas.cacheDir=E:/Skycraft-scratch/codex-grid-evidence/parity-10-cold/generated-cache '
            b'-Dnirncraft.atlas.memoryDiagnostics=true '
            b'-Dnirncraft.atlas.clientGen=true -Dnirncraft.atlas.profile=true '
            b'-Dnirncraft.atlas.safeMode=false -Dnirncraft.atlas.unified=true '
            b'-Dnirncraft.autotest.keepWorld=true -Dnirncraft.autotest.world=harness_b39df6591096 '
            b'-Dnirncraft.harness.end=1"\r\n'
        )
        cleaned = parity_capture.sanitize_instance_cfg(dirty)
        expected = (
            b'[General]\r\n'
            b'JvmArgs="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5006 '
            b'-XX:+UseG1GC -XX:MaxGCPauseMillis=200 '
            b'-Dnirncraft.harness=1 '
            b'-Dnirncraft.atlas.clientGen=true -Dnirncraft.atlas.profile=true '
            b'-Dnirncraft.atlas.safeMode=false -Dnirncraft.atlas.unified=true '
            b'-Dnirncraft.autotest.keepWorld=true -Dnirncraft.autotest.world=harness_b39df6591096 '
            b'-Dnirncraft.harness.end=1"\r\n'
        )
        self.assertEqual(expected, cleaned)

    def test_snapshot_and_restore_launcher_state_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cfg = root / 'instance.cfg'
            flags = root / 'nirncraft-harness-flags.properties'
            cfg.write_bytes(
                b'[General]\n'
                b'JvmArgs="-XX:+UseG1GC -Dnirncraft.atlas.clientGen=true '
                b'-Dnirncraft.harness=1 -Dnirncraft.atlas.cacheDir=/tmp/cold '
                b'-Dnirncraft.atlas.clientGen=true -Dnirncraft.harness.end=1"\n'
            )
            flags.write_bytes(
                b'nirncraft.atlas.clientGen=true\n'
                b'nirncraft.atlas.cacheDir=/tmp/cold\n'
                b'nirncraft.atlas.memoryDiagnostics=true\n'
                b'nirncraft.harness.authoritative=true\n'
            )
            saved = parity_capture.snapshot_launcher_state(root, flags)
            # Simulate parity_capture --cold writing temporary flags during the run
            cfg.write_bytes(b'[General]\nJvmArgs="-XX:+UseG1GC -Dnirncraft.harness=1 -Dnirncraft.atlas.cacheDir=/tmp/new -Dnirncraft.harness.end=1"\n')
            flags.write_bytes(b'nirncraft.atlas.cacheDir=/tmp/new\n')
            parity_capture.restore_launcher_state(saved)
            self.assertEqual(
                b'[General]\nJvmArgs="-XX:+UseG1GC -Dnirncraft.harness=1 -Dnirncraft.atlas.clientGen=true -Dnirncraft.harness.end=1"\n',
                cfg.read_bytes(),
            )
            self.assertEqual(
                b'nirncraft.atlas.clientGen=true\nnirncraft.harness.authoritative=true\n',
                flags.read_bytes(),
            )


if __name__ == '__main__':
    unittest.main()
