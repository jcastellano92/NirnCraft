import importlib.util
import hashlib
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('coverage',Path(__file__).with_name('world_coverage.py'))
coverage=importlib.util.module_from_spec(spec);spec.loader.exec_module(coverage)


class CoverageTest(unittest.TestCase):
    def test_current_visual_requires_unchanged_actual_image_and_geometry(self):
        with tempfile.TemporaryDirectory() as folder:
            image=Path(folder)/'frame.png';image.write_bytes(b'actual fixture')
            identity={'seed':1,'dimension':'minecraft:overworld','generationInputs':'geometry'}
            row={'status':'passed','tier':'visual','evidence':str(image),'_shaderHash':'shader',
                 'receipt':{**identity,'coreSha256':'core','shaders':True,'cameraClear':True,
                            'readinessPassed':True,'nightVision':False},
                 'review':{'imageSha256':hashlib.sha256(image.read_bytes()).hexdigest()}}
            state=lambda:coverage.evidence_state(row,identity,'core','shader',Path(folder))
            self.assertEqual('current_visual',state())
            identity['generationInputs']='changed';self.assertEqual('stale_generation',state())
            identity['generationInputs']='geometry';image.write_bytes(b'replaced')
            self.assertEqual('unverified_image',state())

    def test_global_contract_and_selected_region_never_count_as_visual(self):
        spec={'systems':[{'id':'magic','conditions':['player_spell_hit']} ]}
        runs=[{'id':'global','regions':['a','b'],'results':[{'id':'magic','tier':'runtime',
                'status':'passed','systems':['magic'],'receipt':{'coreSha256':'core'}}]}]
        result=coverage.build(spec,[{'id':'a'},{'id':'b'}],runs,{},'core','shader',Path('.'))
        self.assertTrue(all(c['visual']=='missing_current_visual' for c in result['cases']))
        self.assertEqual(1,len(result['observationsWithoutConditionCoverage']))


if __name__=='__main__':unittest.main()
