import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('scenario',Path(__file__).with_name('world_scenario.py'))
scenario=importlib.util.module_from_spec(spec);spec.loader.exec_module(scenario)


class ScenarioTest(unittest.TestCase):
    def test_damage_is_not_proved_by_a_boolean_or_missing_authority(self):
        self.assertFalse(scenario.assertion({'healthLost':True},{'field':'healthLost','atLeast':.1}))
        self.assertFalse(scenario.assertion({}, {'field':'damageFromObserver','equals':True}))
        self.assertFalse(scenario.assertion({'damageFromObserver':1},{'field':'damageFromObserver','equals':True}))
        self.assertTrue(scenario.assertion({'healthLost':.4},{'field':'healthLost','atLeast':.1}))
    def test_artifact_path_cannot_escape_game(self):
        with self.assertRaises(ValueError):scenario.artifact(Path('C:/test/game'),'../other.json')


if __name__=='__main__':unittest.main()
