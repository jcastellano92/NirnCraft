"""A roof above a blocked road must not turn a hay obstruction into a cliff."""
import unittest
import numpy as np
from types import SimpleNamespace
from road_measure import covered_point


class RoofTest(unittest.TestCase):
    def test_blocked_floor_remains_failure_at_the_actual_walking_height(self):
        names=['minecraft:air','minecraft:dark_oak_planks','minecraft:hay_block','minecraft:deepslate_tiles']
        column=np.zeros(100,dtype=int);column[70]=1;column[71:73]=2;column[85]=3
        cols=SimpleNamespace(w=SimpleNamespace(names=names),column=lambda x,z:(0,column))
        self.assertIsNone(covered_point(cols,0,0,71))
        point=covered_point(cols,0,0,71,allow_blocked=True)
        self.assertEqual(71,point['h']);self.assertTrue(point['obstacle'])
        column[71:73]=0
        point=covered_point(cols,0,0,71)
        self.assertEqual(71,point['h']);self.assertFalse(point['obstacle'])


if __name__=='__main__':unittest.main()
