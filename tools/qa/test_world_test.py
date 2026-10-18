"""Runner must not turn missing checks or stale evidence into a pass."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock
import json
from types import SimpleNamespace

spec=importlib.util.spec_from_file_location('world_test',Path(__file__).with_name('world_test.py'))
qa=importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)


class RunnerTest(unittest.TestCase):
    def test_exit_zero_cannot_hide_reported_errors_or_missing_summary(self):
        check={'requireRegex':['0 errors']}
        self.assertEqual('passed',qa.verdict(check,0,'Checked 93 records, 0 errors'))
        self.assertEqual('failed',qa.verdict(check,0,'ERR blocked road\n0 errors'))
        self.assertEqual('failed',qa.verdict(check,0,''))
        self.assertEqual('failed',qa.verdict(check,1,'0 errors'))

    def test_unknown_selections_are_errors_not_empty_success(self):
        values=[{'id':'skyrim/falkreath','province':'skyrim'}]
        self.assertEqual(values,qa.selected(values,'skyrim'))
        with self.assertRaises(ValueError): qa.selected(values,'falkreath_typo')

    def test_data_change_invalidates_fast_reuse_but_timestamp_does_not(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            data=root/'map.json'
            data.write_text('{"height":3}')
            with patch.object(qa,'ROOT',root):
                original=qa.fingerprint(['*.json'])
                data.touch()
                self.assertEqual(original,qa.fingerprint(['*.json']))
                data.write_text('{"height":4}')
                self.assertNotEqual(original,qa.fingerprint(['*.json']))

    def test_wrong_shader_performance_run_is_retained_as_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); presets=root/'cameras.json'
            presets.write_text(json.dumps({'cameras':{'cave':{'position':[0,1,0], 'lookAt':[0,1,2],
                                           'systems':['performance'],'region':'skyrim/falkreath'}}}))
            identity=root/'world-preview/camera-identity.json';identity.parent.mkdir();identity.write_text('{}')
            sample=root/'build/autotest-performance/run-cave.json';sample.parent.mkdir(parents=True)
            sample.write_text(json.dumps({'summary':{'samples':120},'shaders':False}))
            live=SimpleNamespace(GAME=root,require_ready=Mock(),live_actions=Mock())
            cameras=SimpleNamespace(PRESETS=presets,same_generation=Mock(return_value=True),validate_receipt=Mock())
            folder=root/'result';data={'id':'run','kind':'performance','regions':[], 'results':[]}
            with patch.object(qa,'module',side_effect=[live,cameras]),patch.object(qa,'new_run',return_value=(folder,data)):
                self.assertEqual(1,qa.measure('cave',[],[],120,'on'))
            saved=json.loads((folder/'run.json').read_text())
            self.assertEqual('failed',saved['results'][0]['status'])
            self.assertTrue((folder/'samples.json').is_file())


if __name__=='__main__': unittest.main()
