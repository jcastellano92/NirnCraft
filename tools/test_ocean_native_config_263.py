import importlib.util
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('ocean_native',Path(__file__).parent/'qa/ocean_loop_smoke.py')
ocean=importlib.util.module_from_spec(spec);spec.loader.exec_module(ocean)


class NativeOceanConfigTest(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup)
        self.game=Path(temporary.name)
        self.save=self.game/'saves/qa_world_263';self.save.mkdir(parents=True)
        (self.save/'level.dat').write_bytes(b'test save marker')
        self.base=self.game/'config/nirncraft-ocean-server.toml';self.base.parent.mkdir()
        self.base.write_text('[outerOcean]\nwrapEnabled=true\nhalfWidth=29360128\n')

    def test_world_synced_override_has_native_priority(self):
        override=self.save/'syncedconfig/nirncraft-ocean-server.toml';override.parent.mkdir()
        override.write_text('[outerOcean]\nwrapEnabled=true\nhalfWidth=65536\n')
        self.assertEqual(override,ocean.effective_config(self.game,'qa_world_263'))

    def test_missing_world_override_uses_instance_config_and_ignores_legacy_seeding_files(self):
        for path in (self.save/'serverconfig/nirncraft-ocean-server.toml',self.game/'defaultconfigs/nirncraft-ocean-server.toml'):
            path.parent.mkdir();path.write_text('legacy=true')
        self.assertEqual(self.base,ocean.effective_config(self.game))
        self.base.unlink()
        with self.assertRaisesRegex(ValueError,'No native ocean configuration'):
            ocean.effective_config(self.game)

    def test_multiple_saves_require_explicit_selection(self):
        another=self.game/'saves/another';another.mkdir();(another/'level.dat').write_bytes(b'another marker')
        with self.assertRaisesRegex(ValueError,'Specify --world'):
            ocean.effective_config(self.game)
        self.assertEqual(self.base,ocean.effective_config(self.game,'qa_world_263'))


if __name__=='__main__':unittest.main()
