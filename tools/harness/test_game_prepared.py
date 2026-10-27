"""Prepared runtime comparisons never accept stale classes or native payloads."""
import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZIP_STORED
import game


class PreparedCoreTest(unittest.TestCase):
    def write(self, path, *, manifest=None, changed=None, compression=ZIP_STORED):
        obj = {"format": 1, "inputs": "same-generation", "files": ["landmass.bin"]}
        data = {"com/nirncraft/atlas/AtlasClient.class": b"current-class",
                "natives/windows-x86_64/atlas_core.dll": b"current-native",
                "nirncraft/baked/landmass.bin": b"same-bake",
                "nirncraft/baked/manifest.json": manifest or json.dumps(obj).encode()}
        data.update(changed or {})
        with ZipFile(path, "w", compression) as z:
            for name, raw in data.items(): z.writestr(name, raw)

    def test_container_and_manifest_formatting_may_change(self):
        with tempfile.TemporaryDirectory() as d:
            a,b=Path(d)/"a.jar",Path(d)/"b.jar"
            self.write(a)
            self.write(b, manifest=b'{\n "files":["landmass.bin"],"inputs":"same-generation","format":1\n}',compression=ZIP_DEFLATED)
            self.assertTrue(game.prepared_core_matches(a,b))

    def test_class_native_bake_and_manifest_changes_are_rejected(self):
        changes = [{"com/nirncraft/atlas/AtlasClient.class": b"stale"},
                   {"natives/windows-x86_64/atlas_core.dll": b"stale"},
                   {"nirncraft/baked/landmass.bin": b"stale"},
                   {"nirncraft/baked/manifest.json": b'{"format":1,"inputs":"different","files":["landmass.bin"]}'},
                   {"extra.class": b"unexpected"}]
        with tempfile.TemporaryDirectory() as d:
            a,b=Path(d)/"a.jar",Path(d)/"b.jar";self.write(a)
            for changed in changes:
                with self.subTest(changed=next(iter(changed))):
                    self.write(b,changed=changed)
                    self.assertFalse(game.prepared_core_matches(a,b))

    def test_missing_and_invalid_archives_fail_closed(self):
        with tempfile.TemporaryDirectory() as d:
            a,b=Path(d)/"a.jar",Path(d)/"b.jar";self.write(a)
            self.assertFalse(game.prepared_core_matches(a,b))
            b.write_bytes(b"not a jar")
            self.assertFalse(game.prepared_core_matches(a,b))


if __name__=="__main__":unittest.main()
