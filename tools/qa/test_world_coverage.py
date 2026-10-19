import importlib.util
import hashlib
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('coverage',Path(__file__).with_name('world_coverage.py'))
coverage=importlib.util.module_from_spec(spec);spec.loader.exec_module(coverage)


class CoverageTest(unittest.TestCase):
    def test_outer_ocean_requires_actual_distant_open_sea_receipt(self):
        region={'kind':'outer_ocean','expectedRegion':'sea/open_ocean','minimumAbsCoordinate':32768}
        self.assertIsNone(coverage.region_evidence_state(region,{'regionId':'sea/open_ocean','position':[-65535,64,0]}))
        for receipt in ({'regionId':'sea/open_ocean','position':[0,64,0]},
                        {'regionId':'realm/x9_z9/1','position':[65535,64,0]},
                        {'regionId':'sea/open_ocean','position':[float('inf'),64,0]},
                        {'regionId':'sea/open_ocean','position':[65535,False,0]},
                        {'regionId':'sea/open_ocean','position':None},
                        {'regionId':'sea/open_ocean'}):
            with self.subTest(receipt=receipt):
                self.assertEqual('unverified_outer_ocean',coverage.region_evidence_state(region,receipt))

    def test_region_bucket_requires_actual_world_receipt_not_a_manual_label(self):
        self.assertEqual('unverified_region',coverage.region_evidence_state(
            {'id':'skyrim/falkreath','kind':'authored_region'},{'regionId':'skyrim/whiterun'}))
        realm={'kind':'realm_role','biomes':['nirncraft:coldharbour_hollow_city']}
        self.assertEqual('unverified_biome_role',coverage.region_evidence_state(realm,{'biomeId':'nirncraft:coldharbour_lava_rift'}))
        procedural={'kind':'procedural_archetype','archetype':'frozen'}
        self.assertEqual('unverified_archetype',coverage.region_evidence_state(procedural,{'regionArchetype':'frozen','regionId':'skyrim/winterhold'}))
        self.assertIsNone(coverage.region_evidence_state(procedural,{'regionArchetype':'frozen','regionId':'realm/x9_z-9/1'}))

    def test_realm_evidence_is_checked_against_its_dimension_not_the_active_camera(self):
        with tempfile.TemporaryDirectory() as folder:
            image=Path(folder)/'realm.png';image.write_bytes(b'actual realm image')
            identity={'seed':1,'dimension':'minecraft:overworld','generationInputs':'geometry'}
            row={'status':'passed','tier':'visual','evidence':str(image),'_shaderHash':'shader',
                 'receipt':{**identity,'dimension':'nirncraft:coldharbour','coreSha256':'core',
                            'shaders':True,'cameraClear':True,'readinessPassed':True,'nightVision':False},
                 'review':{'imageSha256':hashlib.sha256(image.read_bytes()).hexdigest()}}
            self.assertEqual('current_visual',coverage.evidence_state(row,identity,'core','shader',Path(folder),'nirncraft:coldharbour'))
            self.assertEqual('stale_generation',coverage.evidence_state(row,identity,'core','shader',Path(folder),'minecraft:the_end'))

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
