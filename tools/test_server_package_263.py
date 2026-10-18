"""Native server package integrity tests. These do not start Minecraft."""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
import zlib

import build_server_instance as builder
from validate_pack import read_lock
from native_server_evidence_263 import command_failure, shutdown_evidence

spec = importlib.util.spec_from_file_location('native_package_qa', Path(__file__).parent / 'qa/test_server_package_263.py')
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)


def jar_bytes(metadata, nested=None):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        archive.writestr('META-INF/neoforge.mods.toml', metadata)
        if nested:
            archive.writestr('META-INF/jarjar/metadata.json', json.dumps({'jars': [{'path': 'META-INF/jarjar/nested.jar'}]}))
            archive.writestr('META-INF/jarjar/nested.jar', nested)
    return output.getvalue()


class ServerPackageIntegrity(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_actual_server_side_selection_keeps_better_combat_dependencies(self):
        rows = builder.server_mods(read_lock())
        keys = {row['key'] for row in rows}
        self.assertEqual(len(rows), 13)
        self.assertIn('better-combat', keys)
        self.assertIn('player-animation-library', keys)
        self.assertTrue(all(row['side'] in ('server', 'both') for row in rows))
        self.assertFalse(keys & {'sodium', 'iris', 'controllable'})

    def test_required_library_cannot_be_silently_filtered_out(self):
        lock = copy.deepcopy(read_lock())
        better = next(row for row in lock['active'] if row['key'] == 'better-combat')
        project = better['requiredDependencies'][0]['project_id']
        next(row for row in lock['active'] if row.get('projectId') == project)['side'] = 'client'
        with self.assertRaisesRegex(ValueError, 'excluded from the server'):
            builder.server_mods(lock)

    def test_installer_args_must_identify_game_separately_from_loader_version(self):
        folder = self.root / builder.NATIVE_ARGS
        folder.mkdir(parents=True)
        text = 'net.neoforged.fml.startup.Server\n--fml.neoForgeVersion 26.3.0.90\n--fml.mcVersion 26.2\n'
        for name in ('win_args.txt', 'unix_args.txt'):
            (folder / name).write_text(text, encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'exact native target'):
            builder.check_install(self.root)

    def test_native_eight_dimension_save_evidence_and_command_failures(self):
        dimensions = {'minecraft:overworld', 'minecraft:the_end', 'minecraft:the_nether',
                      'skycraft:apocrypha', 'skycraft:coldharbour', 'skycraft:soul_cairn',
                      'skycraft:shivering_isles', 'skycraft:jail'}
        log = 'Stopping server\nSaving worlds\n'
        log += ''.join("Saving chunks for level 'ServerLevel[world]'/" + name + '\n' for name in sorted(dimensions))
        log += 'Closing FML Loader 123\nClearing ModLoader\n'
        self.assertEqual(set(shutdown_evidence(log, dimensions)['savedDimensions']), dimensions)
        with self.assertRaisesRegex(ValueError, 'Missing native shutdown saves'):
            shutdown_evidence(log.replace('/skycraft:jail', '/skycraft:other'), dimensions)
        self.assertTrue(command_failure("System chat: Can't find element 'minecraft:daytime' of type 'minecraft:timeline'"))
        self.assertTrue(command_failure('System chat: ...ry daytime<--[HERE]'))
        self.assertFalse(command_failure('System chat: Clock skycraft:fixed/apocrypha is at 18000 tick(s)'))

    def test_real_mod_descriptors_include_nested_server_dependencies(self):
        server = self.root / 'server'
        (server / 'mods').mkdir(parents=True)
        nested = jar_bytes('[[mods]]\nmodId="library"\nversion="1.0"\n')
        metadata = ('[[mods]]\nmodId="gameplay"\nversion="1.0"\n'
                    '[[dependencies.gameplay]]\nmodId="library"\ntype="required"\nside="BOTH"\n'
                    '[[dependencies.gameplay]]\nmodId="client_only_optional_here"\ntype="required"\nside="CLIENT"\n')
        path = server / 'mods/gameplay.jar'
        path.write_bytes(jar_bytes(metadata, nested))
        declarations = builder.declared_server_dependencies(server, [{'filename': path.name}])
        self.assertEqual({item['modId'] for item in declarations}, {'gameplay', 'library'})
        path.write_bytes(jar_bytes(metadata))
        with self.assertRaisesRegex(ValueError, 'missing native server dependency library'):
            builder.declared_server_dependencies(server, [{'filename': path.name}])

    def test_cached_artifacts_must_match_the_lock_even_without_network(self):
        url = 'https://invalid.example.test/server.jar'
        cache = self.root / 'cache'
        cache.mkdir()
        target = self.root / 'server.jar'
        cached = cache / (hashlib.sha256(url.encode()).hexdigest()[:16] + '_server.jar')
        cached.write_bytes(b'correct locked artifact')
        digest = hashlib.sha256(cached.read_bytes()).hexdigest()
        builder.fetch(target, url, digest, 'sha256', cache)
        self.assertEqual(target.read_bytes(), cached.read_bytes())
        cached.write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'incorrect hash'):
            builder.fetch(target, url, digest, 'sha256', cache)
        self.assertEqual(target.read_bytes(), b'correct locked artifact')

    def test_platform_reuse_checks_archive_identity_and_never_copies_old_core(self):
        source = self.root / 'previous.zip'
        receipt = {'target': builder.SERVER_TARGET, 'installer': {'url': builder.INSTALLER_URL, 'sha256': builder.INSTALLER_SHA256}}
        text = 'net.neoforged.fml.startup.Server\n--fml.neoForgeVersion 26.3.0.90\n--fml.mcVersion 26.3\n'
        with zipfile.ZipFile(source, 'w') as archive:
            archive.writestr('server/migration/server-receipt.json', json.dumps(receipt))
            archive.writestr('server/migration/installer.log', 'official installer completed')
            for name in ('win_args.txt', 'unix_args.txt'):
                archive.writestr('server/' + (builder.NATIVE_ARGS / name).as_posix(), text)
            archive.writestr('server/mods/skycraft-core.jar', b'old core must not propagate')
            archive.writestr('server/world/level.dat', b'old world must not propagate')
        target = self.root / 'fresh'
        target.mkdir()
        with self.assertRaisesRegex(ValueError, 'recorded SHA256'):
            builder.reuse_installation(source, 'wrong', target, self.root / 'installer.log')
        self.assertEqual(list(target.iterdir()), [])
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        builder.reuse_installation(source, digest, target, self.root / 'installer.log')
        self.assertFalse((target / 'mods').exists())
        self.assertFalse((target / 'world').exists())
        builder.check_install(target)

    def test_complete_bake_bundle_refuses_a_mixed_generation(self):
        path = self.root / 'core.jar'
        names = ['landmass.bin', 'roadplan.bin', 'streetplan.bin', 'sceneplan.bin']
        expected = 'a' * 64
        def write(mixed):
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('skycraft/baked/manifest.json', json.dumps({'format': 1, 'inputs': expected, 'files': names}))
                for name in names:
                    digest = 'b' * 64 if mixed and name == 'sceneplan.bin' else expected
                    archive.writestr('skycraft/baked/' + name, b'\x00\x40' + digest.encode() + zlib.compress(b'actual plan bytes'))
        write(False)
        self.assertEqual(builder.baked_bundle(path)['inputs'], expected)
        write(True)
        with self.assertRaisesRegex(ValueError, 'Mixed, corrupt or empty'):
            builder.baked_bundle(path)

    def test_launcher_generation_preserves_installer_scripts_and_seed(self):
        server = self.root / 'server'
        server.mkdir()
        (server / 'run.bat').write_text('original official launcher', encoding='utf-8')
        builder.write_launchers(server)
        self.assertEqual((server / 'migration/installer-launchers/run.bat').read_text(), 'original official launcher')
        self.assertIn('level-seed=Nirn', (server / 'server.properties').read_text())
        self.assertFalse((server / 'eula.txt').exists())
        for name in ('run.sh', 'run.ps1'):
            text = (server / name).read_text()
            self.assertIn(builder.TARGET['neoforge'], text)
            self.assertIn('java.specification.version' if name == 'run.sh' else 'java\\.specification\\.version', text)
            self.assertNotIn('@NEOFORGE@', text)

    def test_archive_traversal_and_symlinks_are_rejected_before_writes(self):
        path = self.root / 'unsafe.zip'
        for name in ('server/../../escape', 'server/..\\escape', 'C:/server/escape', 'other/file'):
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('server/innocent', b'also must not be extracted')
                archive.writestr(name, b'unsafe')
            with self.assertRaisesRegex(ValueError, 'Unsafe'):
                qa.extract(path, self.root)
            self.assertFalse((self.root / 'server/innocent').exists())
        with zipfile.ZipFile(path, 'w') as archive:
            link = zipfile.ZipInfo('server/link')
            link.external_attr = 0o120777 << 16
            archive.writestr(link, '../../escape')
        with self.assertRaisesRegex(ValueError, 'symbolic link'):
            qa.extract(path, self.root)


if __name__ == '__main__':
    unittest.main()
