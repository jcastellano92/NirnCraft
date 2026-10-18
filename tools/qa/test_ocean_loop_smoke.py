import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('ocean_smoke',Path(__file__).with_name('ocean_loop_smoke.py'))
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)


class OceanSmokeTest(unittest.TestCase):
    def test_commands_do_not_add_block_centering_to_the_requested_position(self):
        self.assertEqual('29360136.0 100.0 1024.0',smoke.coordinates((29360136,100,1024)))

    def test_parse_real_server_nbt_position_including_large_coordinates(self):
        self.assertEqual([-29360120,100,1024],smoke.entity_position(
            'Player has the following entity data: [-2.936012E7d, 100.0d, 1024.0d]'))
        with self.assertRaises(ValueError):smoke.entity_position('No entity was found')

    def test_cases_preserve_overshoot_on_both_axes(self):
        cases=smoke.crossing_cases(65536)
        self.assertEqual(5,len(cases))
        self.assertEqual(('corner',(65544,100,65548),(-65528,100,-65524)),cases[-1])
        self.assertEqual((65528,100,1024),cases[1][2])


if __name__=='__main__':unittest.main()
