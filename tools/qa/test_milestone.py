"""A scoped archive must still reject failed gates, changed visuals and a different core."""
import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('world_milestone',Path(__file__).resolve().parents[1]/'release/world_milestone.py')
milestone=importlib.util.module_from_spec(spec);spec.loader.exec_module(milestone)


class MilestoneGateTest(unittest.TestCase):
    def test_changed_core_or_unreviewed_image_cannot_ship(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); core=root/'core.jar';core.write_bytes(b'test core')
            build=root/'build.log';build.write_text('BUILD SUCCESSFUL')
            xml=root/'tests';xml.mkdir();(xml/'TEST-contract.xml').write_text('<testsuite tests="3" errors="0" failures="0" skipped="0"/>')
            image=root/'view.png';image.write_bytes(b'image fixture')
            folder=root/'run';folder.mkdir()
            row={'id':'view','tier':'visual','status':'passed','evidence':str(image),
                 'receipt':{'coreSha256':hashlib.sha256(core.read_bytes()).hexdigest(),'shaders':False},
                 'review':{'imageSha256':hashlib.sha256(image.read_bytes()).hexdigest()}}
            (folder/'run.json').write_text(json.dumps({'results':[row]}))
            data={'scope':'test fixture','coreJar':str(core),'buildLog':str(build),'junitDirectory':str(xml),
                  'requiredRuns':[{'path':str(folder),'checks':['view']}]}
            self.assertEqual(3,milestone.validate(data)['tests'])
            image.write_bytes(b'changed after review')
            with self.assertRaisesRegex(ValueError,'changed'): milestone.validate(data)
            image.write_bytes(b'image fixture');core.write_bytes(b'different core')
            with self.assertRaisesRegex(ValueError,'exact staged core'): milestone.validate(data)
            core.write_bytes(b'test core');row['status']='awaiting_review'
            (folder/'run.json').write_text(json.dumps({'results':[row]}))
            with self.assertRaisesRegex(ValueError,'not passed'): milestone.validate(data)


if __name__=='__main__': unittest.main()
