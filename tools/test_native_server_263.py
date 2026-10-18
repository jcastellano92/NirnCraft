"""Launch-file integrity checks; fixtures do not simulate a running Minecraft server."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('native_server_263', Path(__file__).parent / 'dev/native_server_263.py')
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class LaunchIntegrity(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.scratch = self.base / 'scratch'
        self.runtime = self.base / 'native-runtime'
        self.runtime.mkdir()
        self.moddev = self.scratch / 'build/moddev'
        self.moddev.mkdir(parents=True)
        self.classes = self.scratch / 'build/classes/java/main'
        self.core = self.classes / 'com/skycraft/Skycraft.class'
        self.core.parent.mkdir(parents=True)
        self.core.write_bytes(b'\xca\xfe\xba\xbe\0\0\0E')
        self.resources = self.scratch / 'build/resources/main'
        self.resources.mkdir(parents=True)
        (self.resources / 'pack.mcmeta').write_text((native.ROOT / 'mod/src/main/resources/pack.mcmeta').read_text())
        rules_path = Path('resourcepacks/recipe_rules/pack.mcmeta')
        (self.resources / rules_path).parent.mkdir(parents=True)
        (self.resources / rules_path).write_text((native.ROOT / 'mod/src/main/resources' / rules_path).read_text())
        self.mc = self.base / 'minecraft-patched-26.3.0.90.jar'
        with zipfile.ZipFile(self.mc, 'w') as z:
            z.writestr('version.json', json.dumps({'id': '26.3'}))
        self.neo = self.base / 'neoforge-26.3.0.90-universal.jar'
        with zipfile.ZipFile(self.neo, 'w'):
            pass
        self.paths = [self.classes, self.resources, self.mc, self.neo]
        self.write_classpath()
        (self.moddev / 'serverRunVmArgs.txt').write_text('-Dskycraft.dev=' + str(self.runtime).replace('\\', '\\\\'))
        (self.moddev / 'serverRunProgramArgs.txt').write_text('net.neoforged.fml.startup.Server\n--nogui\n')

    def write_classpath(self):
        cp = os.pathsep.join(map(str, self.paths)).replace('\\', '\\\\')
        (self.moddev / 'serverRunClasspath.txt').write_text('-classpath\n"' + cp + '"\n')

    def prepare(self):
        return native.prepare(self.scratch, self.runtime, self.runtime, self.base / 'java.exe', 26777, '4G')

    def test_native_launch_keeps_independent_argfiles(self):
        result = self.prepare()
        self.assertEqual(result['minecraft'], '26.3')
        self.assertIn('-Dskycraft.dev=' + self.runtime.as_posix(), result['args'])
        self.assertIn('-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:26777', result['args'])
        (self.moddev / 'serverRunProgramArgs.txt').write_text('changed by another prepare task')
        self.assertIn('--nogui', (self.runtime / 'native-launch/serverRunProgramArgs.txt').read_text())

    def test_rejects_different_control_directory(self):
        (self.moddev / 'serverRunVmArgs.txt').write_text('-Dskycraft.dev=another-world')
        with self.assertRaisesRegex(ValueError, 'control directory'):
            self.prepare()

    def test_rejects_old_core_in_runtime_classpath(self):
        old = self.base / 'old-core.jar'
        with zipfile.ZipFile(old, 'w') as z:
            z.writestr('com/skycraft/Skycraft.class', b'old')
        self.paths.append(old)
        self.write_classpath()
        with self.assertRaisesRegex(ValueError, 'old core'):
            self.prepare()

    def test_rejects_old_java_and_wrong_native_game(self):
        self.core.write_bytes(b'\xca\xfe\xba\xbe\0\0\0=')
        with self.assertRaisesRegex(ValueError, 'Java 25'):
            self.prepare()
        self.core.write_bytes(b'\xca\xfe\xba\xbe\0\0\0E')
        with zipfile.ZipFile(self.mc, 'w') as z:
            z.writestr('version.json', json.dumps({'id': '26.2'}))
        with self.assertRaisesRegex(ValueError, 'exact version 26.3'):
            self.prepare()

    def test_rejects_invalid_or_stale_processed_metadata(self):
        metadata = self.resources / 'pack.mcmeta'
        data = json.loads(metadata.read_text())
        data['filter'] = {'block': [{'namespace': 'minecraft', 'path': 'unexpected_recipe'}]}
        metadata.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'metadata is stale'):
            self.prepare()
        metadata.write_text('{"filter":{"path":"recipe\\.json"}}')
        with self.assertRaises(json.JSONDecodeError):
            self.prepare()


if __name__ == '__main__':
    unittest.main()
