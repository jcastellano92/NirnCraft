"""Preview snapshots must not expose half-written JSON or accept mixed/stale bakes."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zlib
import hashlib
import zipfile
import contextlib
import io

spec = importlib.util.spec_from_file_location("live_world", Path(__file__).with_name("live_world.py"))
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)


class PreviewTest(unittest.TestCase):
    def test_install_preserves_runtime_bakes_only_when_every_other_change_is_qa(self):
        for changed in ('com/skycraft/client/autotest/VisualCamera.class', 'com/skycraft/dungeons/world/Builder.class'):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as temp:
                root=Path(temp); game=root/'game'; dev=game/'world-preview'
                core=game/'mods/skycraft-core.jar'; core.parent.mkdir(parents=True)
                (dev/'outbox').mkdir(parents=True)
                inputs='a'*64
                names=[n+'.bin' for n in ('landmass','roadplan','streetplan','sceneplan')]
                raw=b'\x00\x40'+inputs.encode()+zlib.compress(b'verified runtime payload')
                entries={**{'skycraft/baked/'+n:raw for n in names},
                         'skycraft/baked/manifest.json':json.dumps({'format':1,'inputs':inputs,'files':names}).encode(),
                         changed:b'old class'}
                with zipfile.ZipFile(core,'w') as z:
                    for name,data in entries.items(): z.writestr(name,data)
                (dev/'outbox/runtime.json').write_text(json.dumps({'coreSha256':hashlib.sha256(core.read_bytes()).hexdigest()}))
                (dev/'outbox/bake-packaged-inputs').write_text(inputs)
                fresh=root/'new.jar'
                entries[changed]=b'new class'
                entries['skycraft/baked/roadplan.bin']=b'source mapped bake'
                with zipfile.ZipFile(fresh,'w') as z:
                    for name,data in entries.items(): z.writestr(name,data)
                with patch.object(live,'GAME',game), patch.object(live,'DEV',dev), patch.object(live,'processes',return_value=[]):
                    with contextlib.redirect_stdout(io.StringIO()): live.install_core(fresh)
                with zipfile.ZipFile(core) as z:
                    expected=raw if '/autotest/' in changed else b'source mapped bake'
                    self.assertEqual(expected,z.read('skycraft/baked/roadplan.bin'))
                    self.assertEqual(b'new class',z.read(changed))

    def test_bake_dependency_digest_includes_paths_code_and_data_but_not_unrelated_code(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'mod/src/main/java/com/skycraft/bake/Bake.java';source.parent.mkdir(parents=True)
            source.write_text('private static final int FORMAT=1; String[] MAP_FILES={"regions.json"}; String[] DATA_FILES={"/skycraft/rules.json"}; String[] CLASSES={"com.skycraft.Plan"};')
            entries={'skycraft/tamriel/regions.json':b'[]','skycraft/rules.json':b'{}',
                     'com/skycraft/Plan.class':b'planner','com/skycraft/client/UI.class':b'ui'}
            def hashed():
                path=root/'test.jar'
                with zipfile.ZipFile(path,'w') as jar:
                    for name,value in entries.items():jar.writestr(name,value)
                with zipfile.ZipFile(path) as jar,patch.object(live,'REPO',root):return live.jar_bake_inputs(jar)
            original=hashed();entries['com/skycraft/client/UI.class']=b'new ui'
            self.assertEqual(original,hashed())
            entries['com/skycraft/Plan.class']=b'changed planner'
            self.assertNotEqual(original,hashed())
            entries['com/skycraft/Plan.class']=b'planner';entries['skycraft/rules.json']=b'{"road":2}'
            self.assertNotEqual(original,hashed())

    def test_invalid_json_preserves_active_pointer(self):
        with tempfile.TemporaryDirectory() as temp:
            repo, dev = Path(temp) / "repo", Path(temp) / "dev"
            source = repo / "mod/src/main/resources/skycraft/tamriel/palettes.json"
            source.parent.mkdir(parents=True)
            source.write_text('{"palettes": {}}')
            with patch.object(live, "REPO", repo), patch.object(live, "DEV", dev):
                first, _ = live.snapshot("palettes")
                source.write_text('{"broken":')
                with self.assertRaises(ValueError):
                    live.snapshot("palettes")
                self.assertEqual(first, live.read_json(dev / "resources/current.json")["snapshot"])

    def test_snapshot_retains_other_groups_and_removes_deleted_overrides(self):
        with tempfile.TemporaryDirectory() as temp:
            repo, dev = Path(temp) / "repo", Path(temp) / "dev"
            root = repo / "mod/src/main/resources"
            palette = root / "skycraft/tamriel/palettes.json"
            scene = root / "skycraft/scatter/kinds.json"
            for file in (palette, scene):
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('{}')
            with patch.object(live, "REPO", repo), patch.object(live, "DEV", dev):
                live.snapshot("palettes")
                live.snapshot("scenes")
                scene.unlink()
                live.snapshot("scenes")
                files = live.read_json(dev / "resources/current.json")["files"]
                self.assertIn("/skycraft/tamriel/palettes.json", files)
                self.assertNotIn("/skycraft/scatter/kinds.json", files)

    def test_bundle_rejects_corrupt_mixed_and_stale_bakes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            digest = "a" * 64
            names = [n + ".bin" for n in ("landmass", "roadplan", "streetplan", "sceneplan")]
            (root / "manifest.json").write_text(json.dumps({"format": 1, "inputs": digest, "files": names}))
            raw = b'\x00\x40' + digest.encode() + zlib.compress(b"complete plan payload")
            for name in names:
                (root / name).write_bytes(raw)
            self.assertEqual(4, len(live.validate_bakes(root, digest)))
            with self.assertRaisesRegex(ValueError, "differ"):
                live.validate_bakes(root, "b" * 64)
            (root / names[-1]).write_bytes(b'\x00\x40' + b"b" * 64 + zlib.compress(b"other"))
            with self.assertRaisesRegex(ValueError, "Mixed"):
                live.validate_bakes(root, digest)
            (root / names[-1]).write_bytes(raw[:-6])
            with self.assertRaises(zlib.error):
                live.validate_bakes(root, digest)

    def test_new_boot_does_not_activate_unreloaded_old_groups(self):
        with tempfile.TemporaryDirectory() as temp:
            repo, dev = Path(temp) / "repo", Path(temp) / "dev"
            root = repo / "mod/src/main/resources"
            for rel in ("skycraft/tamriel/palettes.json", "skycraft/scatter/kinds.json"):
                path = root / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('{}')
            ready = dev / "outbox/ready"
            ready.parent.mkdir(parents=True)
            ready.write_text('123\nfirst boot\n')
            with patch.object(live, "REPO", repo), patch.object(live, "DEV", dev):
                live.snapshot("scenes")
                ready.write_text('456\nsecond boot\n')
                live.snapshot("palettes")
                files = live.read_json(dev / "resources/current.json")["files"]
                self.assertEqual(["/skycraft/tamriel/palettes.json"], list(files))


if __name__ == "__main__":
    unittest.main()
