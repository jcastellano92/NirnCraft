"""Isolation/integrity tests. Fixture jars are temporary and never distributed or launched."""
import hashlib
import io
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import sys
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("prepare_prism_263", ROOT / "tools/qa/prepare_prism_263.py")
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class IsolatedNativePreviewTest(unittest.TestCase):
    def test_cli_without_optional_server_keeps_integrated_default(self):
        with patch.object(sys, "argv", ["prepare_prism_263.py"]), \
                patch.object(prepare, "prepare", return_value={}) as operation, \
                patch.object(sys, "stdout", io.StringIO()):
            prepare.main()
        self.assertIsNone(operation.call_args.kwargs["server"])

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.instance = self.root / "fixture"
        game = self.instance / ".minecraft"
        for folder in ("mods", "config", "migration"):
            (game / folder).mkdir(parents=True, exist_ok=True)
        (self.instance / "instance.cfg").write_text("name=Fixture\nJvmArgs=-XX:+UseG1GC\nOverrideJavaLocation=false\n")
        (self.instance / "mmc-pack.json").write_text(json.dumps({"components": [
            {"uid": "net.minecraft", "version": "26.3"}, {"uid": "net.neoforged", "version": "26.3.0.90"}]}))
        metadata = '[[mods]]\nmodId="nirncraft"\n[[dependencies.nirncraft]]\nmodId="minecraft"\nversionRange="[26.3]"\n[[dependencies.nirncraft]]\nmodId="neoforge"\n'
        with zipfile.ZipFile(game / "mods/nirncraft-core.jar", "w") as jar:
            jar.writestr("META-INF/neoforge.mods.toml", metadata)
            jar.writestr("com/nirncraft/NirnCraft.class", bytes.fromhex("cafebabe00000045"))
        (game / "mods/dependency.jar").write_bytes(b"temporary fixture")
        self.lock = {"target": prepare.TARGET, "active": [{"filename": "dependency.jar", "side": "both",
            "hashFormat": "sha256", "hash": hashlib.sha256(b"temporary fixture").hexdigest()}]}
        (game / "migration/platform-lock.json").write_text(json.dumps(self.lock))
        (game / "config/iris.properties").write_text("enableShaders=false\nshaderPack=\n")
        self.archive = self.root / "fixture.zip"
        self.zip()

    def zip(self):
        with zipfile.ZipFile(self.archive, "w") as archive:
            for path in self.instance.rglob("*"):
                if path.is_file():
                    archive.write(path, "Fixture/" + path.relative_to(self.instance).as_posix())

    def test_integrity_rejects_extra_legacy_mod_and_wrong_platform(self):
        prepare.validate_instance(self.instance, self.lock)
        extra = self.instance / ".minecraft/mods/old-forge.jar"
        extra.write_bytes(b"legacy")
        with self.assertRaisesRegex(ValueError, "legacy"):
            prepare.validate_instance(self.instance, self.lock)
        extra.unlink()
        manifest = self.instance / "mmc-pack.json"
        manifest.write_text(manifest.read_text().replace('26.3.0.90', '21.1.0'))
        with self.assertRaisesRegex(ValueError, "exactly"):
            prepare.validate_instance(self.instance, self.lock)

    def test_tampered_dependency_is_rejected(self):
        (self.instance / ".minecraft/mods/dependency.jar").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            prepare.validate_instance(self.instance, self.lock)

    def test_archive_cannot_escape_staging(self):
        for name in ("../escape", "Fixture/../../escape", "C:/escape", "Fixture\\..\\..\\escape"):
            with self.subTest(name=name), zipfile.ZipFile(self.archive, "w") as archive:
                archive.writestr(name, "bad")
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                prepare.extract(self.archive, self.root / "staging")
        self.assertFalse((self.root.parent / "escape").exists())

    def test_prepare_preserves_old_instances_and_backs_up_native_saves(self):
        prism = self.root / "Prism"
        instances = prism / "instances"
        for name in ("NirnCraft", "NirnCraft-autotest", prepare.INSTANCE_NAME):
            (instances / name).mkdir(parents=True)
            (instances / name / "keep").write_text(name)
        with patch.object(prepare, "validate_pack", return_value=self.lock), \
             patch.object(prepare, "java_25", return_value=Path("C:/Java25/bin/java.exe")), \
             patch.object(prepare, "exact_processes", return_value=[]):
            result = prepare.prepare(self.archive, prism, None, live=True, tour="Épic:0:0:180")
        self.assertFalse(result["launched"])
        for name in ("NirnCraft", "NirnCraft-autotest"):
            self.assertEqual(name, (instances / name / "keep").read_text())
        self.assertEqual(prepare.INSTANCE_NAME, (Path(result["backup"]) / "keep").read_text())
        native = Path(result["instance"])
        cfg = (native / "instance.cfg").read_text()
        self.assertIn("OverrideJavaLocation=true", cfg)
        self.assertIn("-Dnirncraft.dev=world-preview", cfg)
        self.assertIn("\\u00c9pic", (native / ".minecraft/nirncraft_autotest.properties").read_text())

    def test_busy_instance_is_never_replaced(self):
        prism = self.root / "Prism"
        destination = prism / "instances" / prepare.INSTANCE_NAME
        destination.mkdir(parents=True)
        (destination / "keep").write_text("running")
        with patch.object(prepare, "validate_pack", return_value=self.lock), \
             patch.object(prepare, "java_25", return_value=Path("C:/Java25/bin/java.exe")), \
             patch.object(prepare, "exact_processes", return_value=[1234]), \
             self.assertRaisesRegex(ValueError, "running"):
            prepare.prepare(self.archive, prism, None)
        self.assertEqual("running", (destination / "keep").read_text())

    def test_full_pack_remote_setup_retains_mod_validation_and_no_local_world(self):
        prism = self.root / "RemotePrism"
        with patch.object(prepare, "validate_pack", return_value=self.lock), \
             patch.object(prepare, "java_25", return_value=Path("C:/Java25/bin/java.exe")), \
             patch.object(prepare, "exact_processes", return_value=[]):
            result = prepare.prepare(self.archive, prism, None, live=True, server="127.0.0.1:25576")
        native = Path(result["instance"])
        prepare.validate_instance(native, self.lock)
        props = (native / ".minecraft/nirncraft_autotest.properties").read_text()
        self.assertIn("nirncraft.autotest.server=127.0.0.1:25576", props)
        self.assertIn("nirncraft.autotest.world=\n", props)
        receipt = json.loads((native / ".minecraft/migration/preview-receipt.json").read_text())
        self.assertEqual("tcp", receipt["expectedTransport"])
        self.assertFalse(receipt["runtimeVerified"])
        self.assertFalse(result["launched"])

    def test_full_preparation_refuses_a_running_diagnostic(self):
        prism = self.root / "Prism"
        with patch.object(prepare, "validate_pack", return_value=self.lock), \
             patch.object(prepare, "java_25", return_value=Path("C:/Java25/bin/java.exe")), \
             patch.object(prepare, "exact_processes", side_effect=[[], [1234]]), \
             self.assertRaisesRegex(ValueError, "diagnostic is running"):
            prepare.prepare(self.archive, prism, None)
        self.assertFalse((prism / "instances" / prepare.INSTANCE_NAME).exists())


if __name__ == "__main__":
    unittest.main()
