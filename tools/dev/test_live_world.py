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

# These fixtures test bake validity after the real native platform check passes.
NATIVE_CORE = {
    'META-INF/neoforge.mods.toml': b'''license="MIT"
[[mods]]
modId="nirncraft"
version="0.1.0"
[[dependencies.nirncraft]]
modId="minecraft"
type="required"
versionRange="[26.3]"
[[dependencies.nirncraft]]
modId="neoforge"
type="required"
versionRange="[26.3.0.90,26.4)"
''',
    # Only the class header is inspected by the offline platform validator.
    'com/nirncraft/NirnCraft.class': b'\xca\xfe\xba\xbe\0\0\0\x45',
}


class StatusSnapshotTest(unittest.TestCase):
    def test_locked_startup_snapshot_stays_pending_then_recovers(self):
        for name in ('ready', 'heartbeat'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as folder:
                dev = Path(folder); (dev / 'outbox').mkdir(); (dev / 'inbox').mkdir()
                (dev / 'outbox/ready').write_text('7\n')
                (dev / 'outbox/heartbeat').write_text('alive')
                original_read = Path.read_text
                locked = [True]
                def read_snapshot(path, *args, **kwargs):
                    if path == dev / 'outbox' / name and locked[0]:
                        locked[0] = False
                        raise PermissionError('server is replacing the snapshot')
                    return original_read(path, *args, **kwargs)
                current = live.time.time()
                with patch.object(live, 'DEV', dev), patch.object(live, 'processes', return_value=[{'pid': 7}]), \
                     patch.object(live, 'read_json', return_value={'pid': 7, 'updated': current * 1000}), \
                     patch.object(Path, 'read_text', autospec=True, side_effect=read_snapshot):
                    first = live.status()
                    self.assertFalse(first['serverReady'])
                    self.assertEqual('', first['serverHeartbeat'])
                    second = live.status()
                    self.assertTrue(second['serverReady'])
                    self.assertEqual('alive', second['serverHeartbeat'])


class LaunchBudgetTest(unittest.TestCase):
    def test_ordinary_calls_keep_existing_limits(self):
        with patch.dict(live.os.environ, {}, clear=True):
            self.assertIsNone(live.remaining_timeout())
            self.assertEqual(180, live.remaining_timeout(180))

    def test_budget_never_invents_time(self):
        with patch.dict(live.os.environ, {"NIRNCRAFT_HARNESS_DEADLINE": "105"}, clear=True), patch.object(live.time, "monotonic", return_value=100):
            self.assertEqual(5, live.remaining_timeout())
            self.assertEqual(2, live.remaining_timeout(2))
        with patch.dict(live.os.environ, {"NIRNCRAFT_HARNESS_DEADLINE": "100"}, clear=True), patch.object(live.time, "monotonic", return_value=100):
            with self.assertRaises(TimeoutError): live.remaining_timeout()

    def test_invalid_deadlines_fail_closed(self):
        for raw in ("nan", "inf", "oops"):
            with self.subTest(raw=raw), patch.dict(live.os.environ, {"NIRNCRAFT_HARNESS_DEADLINE": raw}, clear=True):
                with self.assertRaises(ValueError): live.remaining_timeout()

    def test_cancellation_prevents_launcher_even_with_time_left(self):
        with tempfile.TemporaryDirectory() as d:
            cancel = Path(d)/"cancel"; cancel.touch(); ack = Path(d)/"ack.json"
            env = {"NIRNCRAFT_HARNESS_CANCEL_FILE": str(cancel), "NIRNCRAFT_HARNESS_LAUNCH_ACK_FILE": str(ack)}
            with patch.dict(live.os.environ, env, clear=True), patch.object(live.subprocess, "Popen") as start:
                with self.assertRaisesRegex(RuntimeError, "canceled"): live.request_preview_launch("launcher")
                start.assert_not_called()
            self.assertEqual("prevented", json.loads(ack.read_text())["state"])

    def test_expired_helper_cannot_issue_late_launch(self):
        with tempfile.TemporaryDirectory() as d:
            ack=Path(d)/"ack.json"
            env={"NIRNCRAFT_HARNESS_DEADLINE":"1", "NIRNCRAFT_HARNESS_LAUNCH_ACK_FILE":str(ack)}
            with patch.dict(live.os.environ, env, clear=True), patch.object(live.time,"monotonic",return_value=2), patch.object(live.subprocess,"Popen") as start:
                with self.assertRaises(TimeoutError): live.request_preview_launch("launcher")
                start.assert_not_called()
            self.assertEqual("prevented", json.loads(ack.read_text())["state"])

    def test_successful_handoff_records_ack_without_credentials(self):
        with tempfile.TemporaryDirectory() as d:
            ack=Path(d)/"ack.json"
            with patch.dict(live.os.environ,{"NIRNCRAFT_HARNESS_LAUNCH_ACK_FILE":str(ack)},clear=True), patch.object(live.subprocess,"Popen") as start:
                start.return_value.pid=123
                live.request_preview_launch("launcher", "kept-test-world")
                self.assertIn("--world", start.call_args.args[0])
            receipt=json.loads(ack.read_text())
            self.assertEqual("requested",receipt["state"])
            self.assertEqual(123,receipt["launcherPid"])
            self.assertEqual({"state","at","launcherPid"},set(receipt))


class PreviewTest(unittest.TestCase):
    def test_close_waits_through_the_shutdown_silence(self):
        # a close: the heartbeat goes stale while the game saves and exits; that is the close working, not a hang
        fresh = {"fresh": True, "controlVersion": 0}
        stale = {"controlVersion": 0, "queued": 1, "updated": 0, "screen": "game"}
        alive = iter([True, True, True, False])
        with tempfile.TemporaryDirectory() as d, patch.object(live, "GAME", Path(d)),                 patch.object(live, "status", return_value={"client": fresh}),                 patch.object(live, "read_json", return_value=stale),                 patch.object(live, "heartbeat_age", return_value=120.0),                 patch.object(live, "processes", side_effect=lambda: next(alive, True)),                 patch.object(live.time, "sleep", lambda s: None):
            live._live_actions(["stop"], timeout=30, cancel=True)  # returns once the process is gone, no error
            with self.assertRaises(RuntimeError):  # an ordinary step still fails fast on a stale heartbeat
                live._live_actions(["wait 1"], timeout=30, cancel=False)

    def test_verified_staged_bake_replaces_stale_embedded_bake_without_weakening_the_guard(self):
        for valid in (True,False):
            with self.subTest(valid=valid),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);game=root/'game';dev=game/'world-preview';(dev/'outbox').mkdir(parents=True)
                core=game/'mods/nirncraft-core.jar';core.parent.mkdir();new=root/'new.jar'
                expected='a'*64;stale='b'*64
                for path,key in ((core,stale),(new,expected if valid else stale)):
                    raw=b'\x00\x40'+key.encode()+zlib.compress(b'plan')
                    names=[n+'.bin' for n in ('landmass','roadplan','streetplan','sceneplan')]
                    with zipfile.ZipFile(path,'w') as z:
                        for name,data in NATIVE_CORE.items():z.writestr(name,data)
                        z.writestr('com/nirncraft/bake/Bake.class',b'same-validator')
                        z.writestr('nirncraft/baked/manifest.json',json.dumps({'format':1,'inputs':key,'files':names}))
                        for name in names:z.writestr('nirncraft/baked/'+name,raw)
                original=core.read_bytes()
                (dev/'outbox/runtime.json').write_text(json.dumps({'coreSha256':hashlib.sha256(original).hexdigest()}))
                (dev/'outbox/bake-packaged-inputs').write_text(expected)
                with patch.object(live,'GAME',game),patch.object(live,'DEV',dev),patch.object(live,'processes',return_value=[]), \
                        patch.object(live,'jar_bake_inputs',return_value=expected),contextlib.redirect_stdout(io.StringIO()):
                    if valid:live.install_core(new)
                    else:
                        # (a9800b69) a stale bake never blocks a code install: the code goes in with a warning, the
                        # game computes the plans at start
                        err=io.StringIO()
                        with contextlib.redirect_stderr(err):live.install_core(new)
                        self.assertIn('WARNING: no packaged bake matches',err.getvalue())
                if valid:
                    with zipfile.ZipFile(core) as z:
                        self.assertEqual(expected,json.loads(z.read('nirncraft/baked/manifest.json'))['inputs'])

    def test_staging_runtime_bakes_requires_exact_dependencies_and_does_not_touch_installed_core(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);game=root/'game';dev=game/'world-preview';(dev/'outbox').mkdir(parents=True)
            core=game/'mods/nirncraft-core.jar';core.parent.mkdir();candidate=root/'candidate.jar'
            expected='b'*64;bakes=root/'bakes';bakes.mkdir()
            names=[n+'.bin' for n in ('landmass','roadplan','streetplan','sceneplan')]
            raw=b'\x00\x40'+expected.encode()+zlib.compress(b'verified plan')
            for name in names:(bakes/name).write_bytes(raw)
            (bakes/'manifest.json').write_text(json.dumps({'format':1,'inputs':expected,'files':names}))
            for path in (core,candidate):
                with zipfile.ZipFile(path,'w') as archive:
                    for name,data in NATIVE_CORE.items():archive.writestr(name,data)
                    archive.writestr('com/nirncraft/bake/Bake.class',b'same-validator')
                    archive.writestr('dependency',expected)
                    archive.writestr('nirncraft/baked/manifest.json',b'stale')
                    archive.writestr('unrelated-rendering',b'new' if path==candidate else b'old')
            original=core.read_bytes();old_digest=hashlib.sha256(original).hexdigest()
            (dev/'outbox/runtime.json').write_text(json.dumps({'coreSha256':old_digest}))
            (dev/'outbox/bake-packaged-inputs').write_text(expected)
            with patch.object(live,'GAME',game),patch.object(live,'DEV',dev), \
                    patch.object(live,'jar_bake_inputs',side_effect=lambda z:z.read('dependency').decode()), \
                    contextlib.redirect_stdout(io.StringIO()):
                staged=root/'staged.jar';live.stage_core(candidate,bakes,staged)
                self.assertEqual(original,core.read_bytes())
                with zipfile.ZipFile(staged) as archive:
                    self.assertEqual(b'new',archive.read('unrelated-rendering'))
                    self.assertEqual(raw,archive.read('nirncraft/baked/roadplan.bin'))
                with self.assertRaises(ValueError):live.stage_core(candidate,bakes,staged)
                with patch.object(live,'jar_bake_inputs',return_value='changed'):
                    with self.assertRaises(ValueError):live.stage_core(candidate,bakes,root/'rejected.jar')
                self.assertFalse((root/'rejected.jar').exists())

    def test_install_preserves_runtime_bakes_only_when_every_other_change_is_qa(self):
        for changed in ('com/nirncraft/client/autotest/VisualCamera.class', 'com/nirncraft/dungeons/world/Builder.class'):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as temp:
                root=Path(temp); game=root/'game'; dev=game/'world-preview'
                core=game/'mods/nirncraft-core.jar'; core.parent.mkdir(parents=True)
                (dev/'outbox').mkdir(parents=True)
                inputs='a'*64
                names=[n+'.bin' for n in ('landmass','roadplan','streetplan','sceneplan')]
                raw=b'\x00\x40'+inputs.encode()+zlib.compress(b'verified runtime payload')
                entries={**NATIVE_CORE,**{'nirncraft/baked/'+n:raw for n in names},
                         'nirncraft/baked/manifest.json':json.dumps({'format':1,'inputs':inputs,'files':names}).encode(),
                         changed:b'old class'}
                with zipfile.ZipFile(core,'w') as z:
                    for name,data in entries.items(): z.writestr(name,data)
                (dev/'outbox/runtime.json').write_text(json.dumps({'coreSha256':hashlib.sha256(core.read_bytes()).hexdigest()}))
                (dev/'outbox/bake-packaged-inputs').write_text(inputs)
                fresh=root/'new.jar'
                entries[changed]=b'new class'
                entries['nirncraft/baked/roadplan.bin']=b'source mapped bake'
                with zipfile.ZipFile(fresh,'w') as z:
                    for name,data in entries.items(): z.writestr(name,data)
                with patch.object(live,'GAME',game), patch.object(live,'DEV',dev), patch.object(live,'processes',return_value=[]):
                    with contextlib.redirect_stdout(io.StringIO()): live.install_core(fresh)
                with zipfile.ZipFile(core) as z:
                    expected=raw if '/autotest/' in changed else b'source mapped bake'
                    self.assertEqual(expected,z.read('nirncraft/baked/roadplan.bin'))
                    self.assertEqual(b'new class',z.read(changed))

    def test_bake_dependency_digest_includes_paths_code_and_data_but_not_unrelated_code(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'mod/src/main/java/com/nirncraft/bake/Bake.java';source.parent.mkdir(parents=True)
            source.write_text('private static final int FORMAT=1; String[] MAP_FILES={"regions.json"}; String[] DATA_FILES={"/nirncraft/rules.json"}; String[] CLASSES={"com.nirncraft.Plan"};')
            entries={'nirncraft/tamriel/regions.json':b'[]','nirncraft/rules.json':b'{}',
                     'com/nirncraft/Plan.class':b'planner','com/nirncraft/client/UI.class':b'ui'}
            def hashed():
                path=root/'test.jar'
                with zipfile.ZipFile(path,'w') as jar:
                    for name,value in entries.items():jar.writestr(name,value)
                with zipfile.ZipFile(path) as jar,patch.object(live,'REPO',root):return live.jar_bake_inputs(jar)
            original=hashed();entries['com/nirncraft/client/UI.class']=b'new ui'
            self.assertEqual(original,hashed())
            entries['com/nirncraft/Plan.class']=b'changed planner'
            self.assertNotEqual(original,hashed())
            entries['com/nirncraft/Plan.class']=b'planner';entries['nirncraft/rules.json']=b'{"road":2}'
            self.assertNotEqual(original,hashed())

    def test_invalid_json_preserves_active_pointer(self):
        with tempfile.TemporaryDirectory() as temp:
            repo, dev = Path(temp) / "repo", Path(temp) / "dev"
            source = repo / "mod/src/main/resources/nirncraft/tamriel/palettes.json"
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
            palette = root / "nirncraft/tamriel/palettes.json"
            scene = root / "nirncraft/scatter/kinds.json"
            for file in (palette, scene):
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('{}')
            with patch.object(live, "REPO", repo), patch.object(live, "DEV", dev):
                live.snapshot("palettes")
                live.snapshot("scenes")
                scene.unlink()
                live.snapshot("scenes")
                files = live.read_json(dev / "resources/current.json")["files"]
                self.assertIn("/nirncraft/tamriel/palettes.json", files)
                self.assertNotIn("/nirncraft/scatter/kinds.json", files)

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
            for rel in ("nirncraft/tamriel/palettes.json", "nirncraft/scatter/kinds.json"):
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
                self.assertEqual(["/nirncraft/tamriel/palettes.json"], list(files))


if __name__ == "__main__":
    unittest.main()
