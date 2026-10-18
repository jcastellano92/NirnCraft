import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("loot_modifier_data_263",Path(__file__).with_name("loot_modifier_data_263.py"))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class LootModifierDataTest(unittest.TestCase):
    def test_nested_block_state_filter_keeps_crop_maturity_and_modifier_condition(self):
        old = {"type":"skycraft:add_table","table":"skycraft:harvest/saltrice","conditions":[
            {"condition":"minecraft:block_state_property","block":"minecraft:wheat","properties":{"age":"7"}},
            {"condition":"minecraft:random_chance","chance":0.1}]}
        before = copy.deepcopy(old); converted = m.convert(old)
        self.assertEqual(before,old)
        self.assertEqual({"type":"minecraft:match_block","blocks":"minecraft:wheat","state":{"age":"7"}},converted["condition"]["terms"][0])
        self.assertEqual("minecraft:all_of",converted["condition"]["type"])
        self.assertEqual(0.1,converted["condition"]["terms"][1]["chance"])
        self.assertEqual(converted,m.convert(converted))

if __name__ == "__main__": unittest.main()
