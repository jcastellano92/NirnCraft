"""Failed native bake publication must preserve the previous complete bundle."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zlib

spec = importlib.util.spec_from_file_location('bake_publication', Path(__file__).parent / 'bake/publish_263.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class NativeBakePublication(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.staging = self.root / 'staging'
        self.target = self.root / 'source/baked'
        self.expected = 'a' * 64
        for directory, digest in ((self.staging, self.expected), (self.target, 'b' * 64)):
            directory.mkdir(parents=True)
            for name in publisher.NAMES:
                (directory / name).write_bytes(b'\x00\x40' + digest.encode() + zlib.compress(name.encode()))
            (directory / 'manifest.json').write_text(json.dumps({'format': 1, 'inputs': digest, 'files': publisher.NAMES}), encoding='utf-8')
        self.original = {p.name: p.read_bytes() for p in self.target.iterdir()}
        self.evidence, self.done = self.root / 'job.out', self.root / 'job.done'
        self.evidence.write_text('Bake was stale: rebaked and verified into staging\nRuntime bake inputs: ' + self.expected, encoding='utf-8')
        self.done.write_text('status=OK\nseconds=3\n', encoding='utf-8')

    def publish(self):
        return publisher.publish(self.staging, self.target, self.expected, self.root / 'backup', self.evidence, self.done)

    def assert_source_unchanged(self):
        self.assertEqual({p.name: p.read_bytes() for p in self.target.iterdir()}, self.original)

    def test_success_preserves_previous_bundle_and_unrelated_source_file(self):
        (self.target / 'authored-note.txt').write_text('preserved', encoding='utf-8')
        result = self.publish()
        self.assertTrue(result['nativeVerified'])
        self.assertEqual(publisher.validate(self.target, self.expected), result['files'])
        for name, content in self.original.items():
            self.assertEqual((self.root / 'backup/previous' / name).read_bytes(), content)
        self.assertEqual((self.target / 'authored-note.txt').read_text(), 'preserved')

    def test_failed_job_cannot_publish_existing_staging(self):
        self.done.write_text('status=ERROR\n', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'did not complete'):
            self.publish()
        self.assert_source_unchanged()

    def test_incomplete_or_mixed_bundle_cannot_touch_the_previous_bundle(self):
        (self.staging / 'sceneplan.bin').write_bytes(b'\x00\x40' + b'c' * 64 + zlib.compress(b'wrong generation'))
        with self.assertRaisesRegex(ValueError, 'another generation'):
            self.publish()
        self.assert_source_unchanged()
        self.assertFalse((self.root / 'backup').exists())

    def test_independent_native_mismatch_blocks_publication(self):
        (self.staging / 'roadplan.verify-mismatch.raw').write_bytes(b'new computation differs')
        with self.assertRaisesRegex(ValueError, 'independent-computation mismatch'):
            self.publish()
        self.assert_source_unchanged()

    def test_missing_native_verification_is_not_replaced_by_manifest_presence(self):
        self.evidence.write_text('manifest exists\nRuntime bake inputs: ' + self.expected, encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Missing actual native'):
            self.publish()
        self.assert_source_unchanged()


if __name__ == '__main__':
    unittest.main()
