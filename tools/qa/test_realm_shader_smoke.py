import importlib.util
from pathlib import Path
import unittest
import tempfile
from unittest.mock import Mock

spec=importlib.util.spec_from_file_location('realm_smoke',Path(__file__).with_name('realm_shader_smoke.py'))
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)


class RealmSmokeTest(unittest.TestCase):
    def test_home_is_only_requested_for_actual_supported_realm(self):
        for dimension,calls in [('minecraft:overworld',0),('skycraft:shivering_isles',1),('other:unknown',0)]:
            with self.subTest(dimension=dimension):
                live=Mock();live.DEV=Path('preview');live.read_json.return_value={'dimension':dimension}
                if dimension=='other:unknown':
                    with self.assertRaisesRegex(ValueError,'unrecognized'):smoke.return_home(live)
                else:smoke.return_home(live)
                live.live_actions.assert_called_once_with(['identity'],timeout=30)
                self.assertEqual(calls,live.submit.call_count)

    def test_driver_log_retains_failed_shader_diagnostics_and_rejects_rotation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);log=root/'latest.log';output=root/'case.log'
            before=b'previous realm\n';failure=b'0(612) : error C1503: undefined variable voxel_sampler\n'
            log.write_bytes(before+failure)
            emitted=smoke.retain_driver_log(log,len(before),output)
            self.assertEqual(failure.decode(),emitted)
            self.assertEqual(emitted,output.read_text())
            self.assertIsNotNone(smoke.SHADER_ERRORS.search(emitted))
            log.write_bytes(b'new session')
            with self.assertRaisesRegex(ValueError,'rotated'):
                smoke.retain_driver_log(log,len(before),output)

    def test_shader_errors_are_not_hidden_by_driver_warning_format(self):
        for line in ('ShaderCompileException: failed','0(1081) : error C0000: unexpected token',
                     'Error linking program composite1','error: unknown symbol'):
            with self.subTest(line=line):self.assertIsNotNone(smoke.SHADER_ERRORS.search(line))
        for line in ('0(1081) : warning C7050: may be uninitialized',
                     '[ERROR] [DistantHorizons]: Unexpected error in tick loop'):
            with self.subTest(line=line):self.assertIsNone(smoke.SHADER_ERRORS.search(line))

    def test_dimension_routes_cover_real_ids_including_vanilla_aliases(self):
        self.assertEqual(6,len(smoke.CASES))
        self.assertEqual('minecraft:the_end',smoke.CASES['sovngarde'][0])
        self.assertEqual('minecraft:the_nether',smoke.CASES['deadlands'][0])


if __name__=='__main__':unittest.main()
