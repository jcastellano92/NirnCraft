"""Core-only diagnostics remain separate from full-pack validation and normal instances."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

TOOLS = Path(__file__).parent
sys.path.insert(0, str(TOOLS / 'qa'))
spec = importlib.util.spec_from_file_location('prepare_core_diagnostic_263', TOOLS / 'qa/prepare_core_diagnostic_263.py')
diagnostic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnostic)


class DiagnosticIsolation(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.instance = self.root / diagnostic.DIAGNOSTIC_NAME
        game = self.instance / '.minecraft'
        (game / 'mods').mkdir(parents=True)
        (game / 'migration').mkdir()
        self.core = game / 'mods/nirncraft-core.jar'
        with zipfile.ZipFile(self.core, 'w') as jar:
            jar.writestr('META-INF/neoforge.mods.toml', '[[mods]]\nmodId="nirncraft"\n[[dependencies.nirncraft]]\nmodId="minecraft"\nversionRange="[26.3]"\n[[dependencies.nirncraft]]\nmodId="neoforge"\n')
            jar.writestr('com/nirncraft/NirnCraft.class', bytes.fromhex('cafebabe00000045'))
        marker = {'diagnosticOnly': True, 'fullPackAcceptance': False,
                  'instanceName': diagnostic.DIAGNOSTIC_NAME,
                  'target': {key: diagnostic.TARGET[key] for key in ('minecraft', 'neoforge', 'java')},
                  'coreSha256': hashlib.sha256(self.core.read_bytes()).hexdigest()}
        (game / 'migration/core-diagnostic.json').write_text(json.dumps(marker))
        (self.instance / 'instance.cfg').write_text('name=Diagnostic\nJvmArgs=\n')
        (self.instance / 'mmc-pack.json').write_text(json.dumps({'components': [
            {'uid': 'net.minecraft', 'version': '26.3'}, {'uid': 'net.neoforged', 'version': '26.3.0.90'}]}))
        self.archive = self.root / 'core.zip'
        with zipfile.ZipFile(self.archive, 'w') as archive:
            for path in self.instance.rglob('*'):
                if path.is_file():
                    archive.write(path, diagnostic.DIAGNOSTIC_NAME + '/' + path.relative_to(self.instance).as_posix())

    def test_requires_exact_core_receipt_and_no_other_mods(self):
        diagnostic.validate_diagnostic_instance(self.instance)
        extra = self.core.with_name('sodium.jar')
        extra.write_bytes(b'not part of a core-only test')
        with self.assertRaisesRegex(ValueError, 'only'):
            diagnostic.validate_diagnostic_instance(self.instance)
        extra.unlink()
        with self.core.open('ab') as output:
            output.write(b'changed')
        with self.assertRaisesRegex(ValueError, 'changed since'):
            diagnostic.validate_diagnostic_instance(self.instance)

    def test_server_selection_rejects_non_loopback_or_invalid_ports(self):
        self.assertEqual('127.0.0.1:25576', diagnostic.loopback_server('127.0.0.1:25576'))
        for value in ('localhost:25576', '192.168.1.1:25576', '127.0.0.1:0', '127.0.0.1:65536',
                      '127.0.0.1:25576\nJvmArgs=changed', '127.0.0.1', '../instance'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                diagnostic.loopback_server(value)

    def test_remote_preparation_records_intent_without_claiming_a_connection(self):
        with patch.object(diagnostic, 'java_25', return_value=Path('C:/Java25/bin/java.exe')), \
                patch.object(diagnostic, 'exact_processes', return_value=[]):
            result = diagnostic.prepare(self.archive, self.root / 'RemotePrism', None, '127.0.0.1:25576')
        self.assertEqual('tcp', result['expectedTransport'])
        self.assertFalse(result['runtimeVerified'])
        self.assertFalse(result['launched'])
        instance = Path(result['instance'])
        self.assertIn('-Dnirncraft.autotest.server=127.0.0.1:25576', (instance / 'instance.cfg').read_text())
        self.assertIn('nirncraft.autotest.world=\n', (instance / '.minecraft/nirncraft_autotest.properties').read_text())

    def test_preparation_preserves_full_pack_and_authentication(self):
        prism = self.root / 'Prism'
        for name in ('NirnCraft', 'NirnCraft-autotest', diagnostic.TARGET['instanceName'], diagnostic.DIAGNOSTIC_NAME):
            folder = prism / 'instances' / name
            folder.mkdir(parents=True)
            (folder / 'keep').write_text(name)
        (prism / 'accounts.json').write_text('private account fixture')
        with patch.object(diagnostic, 'java_25', return_value=Path('C:/Java25/bin/java.exe')), \
                patch.object(diagnostic, 'exact_processes', return_value=[]):
            result = diagnostic.prepare(self.archive, prism, None)
        self.assertFalse(result['fullPackAcceptance'])
        self.assertFalse(result['launched'])
        for name in ('NirnCraft', 'NirnCraft-autotest', diagnostic.TARGET['instanceName']):
            self.assertEqual(name, (prism / 'instances' / name / 'keep').read_text())
        self.assertEqual('private account fixture', (prism / 'accounts.json').read_text())
        self.assertEqual(diagnostic.DIAGNOSTIC_NAME, (Path(result['backup']) / 'keep').read_text())
        cfg = (Path(result['instance']) / 'instance.cfg').read_text()
        self.assertIn('name=' + diagnostic.DIAGNOSTIC_NAME, cfg)
        self.assertIn('-Dnirncraft.autotest.live=true', cfg)
        self.assertIn('qa_core_diagnostic_263', (Path(result['instance']) / '.minecraft/nirncraft_autotest.properties').read_text())


if __name__ == '__main__':
    unittest.main()
