import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('cameras',Path(__file__).with_name('visual_cameras.py'))
cameras=importlib.util.module_from_spec(spec)
spec.loader.exec_module(cameras)


class CameraIdentityTest(unittest.TestCase):
    def test_map_seed_and_dimension_changes_require_review_but_new_core_build_does_not(self):
        original={'seed':2428247,'dimension':'minecraft:overworld','generationInputs':'map-a','coreSha':'old'}
        self.assertTrue(cameras.same_generation(original,{**original,'coreSha':'new-lighting-build'}))
        for key,value in [('seed',3),('dimension','minecraft:the_nether'),('generationInputs','map-b')]:
            self.assertFalse(cameras.same_generation(original,{**original,key:value}))
        self.assertFalse(cameras.same_generation(original,{}))
        self.assertFalse(cameras.same_generation({},{}))

    def test_receipt_rejects_missing_readiness_and_debug_lighting(self):
        receipt={'seed':0,'dimension':'minecraft:overworld','generationInputs':'a'*64,
                 'cameraClear':True,'readinessPassed':True,'nightVision':False}
        cameras.validate_receipt(receipt)
        for key in ('cameraClear','readinessPassed','nightVision','generationInputs'):
            bad=dict(receipt)
            bad.pop(key)
            with self.assertRaises(ValueError): cameras.validate_receipt(bad)
        with self.assertRaises(ValueError): cameras.validate_receipt({**receipt,'nightVision':True})


if __name__=='__main__': unittest.main()
