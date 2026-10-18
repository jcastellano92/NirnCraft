import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('geography',Path(__file__).with_name('geography.py'))
geo=importlib.util.module_from_spec(spec);spec.loader.exec_module(geo)


class GeographyTest(unittest.TestCase):
    def test_negative_world_coordinates_and_unknown_are_not_fabricated_land(self):
        o=geo.Oracle({'minX':-256,'minZ':-128,'cell':128,'width':2,'height':1,
                      'indices':[[66,67]],'elevationCentimetres':[[7200,8400]],'foreignLandCells':{'atmora/ice_waste':2}})
        self.assertEqual(66,o.index_at(-256,-128));self.assertEqual(67,o.index_at(-1,-1))
        self.assertIsNone(o.index_at(0,-1));self.assertIsNone(o.index_at(-257,-1))
        self.assertEqual(84,o.elev_at(-1,-1));self.assertAlmostEqual(.032768,o.area('atmora/ice_waste'))


if __name__=='__main__':unittest.main()
