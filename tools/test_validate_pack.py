import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("validate_pack", HERE / "validate_pack.py")
pack_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pack_check)


class ExactPackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.pack = Path(self.temp.name) / "pack"
        shutil.copytree(HERE.parent / "pack", self.pack)

    def write_lock(self, lock):
        (self.pack / "migration/platform-lock.json").write_text(json.dumps(lock), encoding="utf8")

    def test_real_lock_closure_and_index(self):
        self.assertEqual(22, len(pack_check.validate_pack(self.pack)["active"]))

    def test_old_platform_cannot_be_exported(self):
        path = self.pack / "pack.toml"
        path.write_text(path.read_text().replace('minecraft = "26.3"', 'minecraft = "26.2"'))
        with self.assertRaisesRegex(ValueError, "platform lock"):
            pack_check.validate_pack(self.pack)

    def test_approved_beta_requires_its_explicit_bounded_override(self):
        lock = pack_check.read_lock(self.pack)
        lock["dependencyOverrides"] = []
        self.write_lock(lock)
        with self.assertRaisesRegex(ValueError, "missing required exact"):
            pack_check.validate_pack(self.pack)

    def test_override_cannot_authorize_other_artifacts_or_declarations(self):
        import copy
        lock = pack_check.read_lock(self.pack)
        iris = next(r for r in lock["active"] if r["key"] == "iris")
        sodium = next(r for r in lock["active"] if r["key"] == "sodium")
        dependency = iris["requiredDependencies"][0]
        self.assertTrue(pack_check.approved_dependency_override(lock, iris, dependency, sodium))
        for target, field, value in ((iris, "versionId", "other-iris"), (iris, "hash", "changed"),
                                     (sodium, "versionId", "other-sodium"), (sodium, "hash", "changed"),
                                     (dependency, "version_id", "other-original-pin"),
                                     (dependency, "project_id", "other-project")):
            with self.subTest(field=field, value=value):
                changed = copy.deepcopy(target)
                changed[field] = value
                self.assertFalse(pack_check.approved_dependency_override(lock,
                    changed if target is iris else iris, changed if target is dependency else dependency,
                    changed if target is sodium else sodium))

    def test_unknown_override_is_rejected(self):
        lock = pack_check.read_lock(self.pack)
        lock["dependencyOverrides"][0]["resolvedVersionId"] = "unapproved"
        self.write_lock(lock)
        with self.assertRaisesRegex(ValueError, "Unknown or changed"):
            pack_check.validate_pack(self.pack)

    def test_unlisted_legacy_mod_is_rejected(self):
        shutil.copy2(self.pack / "disabled/forge-1.20.1/mods/essential.pw.toml", self.pack / "mods/essential.pw.toml")
        with self.assertRaisesRegex(ValueError, "active metadata"):
            pack_check.validate_pack(self.pack)

    def test_only_nirncrafts_own_shader_pack_can_be_enabled(self):
        path = self.pack / "config/iris.properties"
        path.write_text(path.read_text().replace("shaderPack=InfiniteATLAS.zip", "shaderPack=ComplementaryReimagined.zip"))
        with self.assertRaisesRegex(ValueError, "must be InfiniteATLAS.zip"):
            pack_check.validate_pack(self.pack)

    def test_foreign_shader_pack_files_are_rejected(self):
        (self.pack / "shaderpacks/Other.zip").write_bytes(b"PK")
        with self.assertRaisesRegex(ValueError, "only InfiniteATLAS.zip"):
            pack_check.validate_pack(self.pack)

    def test_tampered_indexed_config_is_rejected(self):
        with (self.pack / "config/defaultoptions/options.txt").open("a") as out:
            out.write("renderDistance:32\n")
        with self.assertRaisesRegex(ValueError, "stale indexed artifact"):
            pack_check.validate_pack(self.pack)

    def core(self, target="[26.3]", major=69, legacy=False):
        jar = Path(self.temp.name) / "core.jar"
        name = "META-INF/mods.toml" if legacy else "META-INF/neoforge.mods.toml"
        metadata = ('[[mods]]\nmodId="nirncraft"\n[[dependencies.nirncraft]]\nmodId="minecraft"\n'
                    f'versionRange="{target}"\n[[dependencies.nirncraft]]\nmodId="neoforge"\n')
        with zipfile.ZipFile(jar, "w") as output:
            output.writestr(name, metadata)
            output.writestr("com/nirncraft/NirnCraft.class", b"\xca\xfe\xba\xbe\0\0" + major.to_bytes(2, "big"))
        return jar

    def test_old_forge_core_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "not a NeoForge"):
            pack_check.validate_core_jar(self.core(legacy=True))

    def test_loose_game_version_or_old_java_core_is_rejected(self):
        for target, major in (("[26.2,26.4)", 69), ("[26.3]", 61)):
            with self.subTest(target=target, major=major), self.assertRaises(ValueError):
                pack_check.validate_core_jar(self.core(target, major))
        pack_check.validate_core_jar(self.core())


if __name__ == "__main__":
    unittest.main()
