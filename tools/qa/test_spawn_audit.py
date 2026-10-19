import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('spawn_audit',Path(__file__).with_name('spawn_audit.py'))
audit=importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)


class SpawnContractTest(unittest.TestCase):
    def test_silent_runtime_skips_and_inverted_ranges_are_reported(self):
        valid={'entity':'nirncraft:deer','count':[1,4],'kind':'wildlife','levels':[1,20]}
        self.assertEqual([],audit.group_errors(valid))
        self.assertEqual([],audit.group_errors({'quiet':True,'weight':3}))
        for bad in ({'weight':0},{'count':[4,1]},{'levels':[20,1]},{'time':'typo'},
                    {'minY':100,'maxY':20},{'townMin':300,'townMax':50}):
            with self.subTest(bad=bad): self.assertTrue(audit.group_errors({**valid,**bad}))
        self.assertTrue(audit.group_errors({'weight':1}))


if __name__=='__main__': unittest.main()
