import importlib.util
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / (name + '.py'))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class WorldRegionTest(unittest.TestCase):
    def test_palette_inheritance_replaces_context_and_micro_arrays_like_runtime(self):
        inventory = load('world_inventory')
        palettes = {'parent': {'contexts': {'land': [{'biome': 'old'}], 'sea': [{'biome': 'sea'}]},
                               'micro': [{'biome': 'inherited'}]},
                    'child': {'fallback': 'parent', 'contexts': {'land': [{'biome': 'new'}]}},
                    'empty': {'fallback': 'child', 'micro': []}}
        self.assertEqual({'new', 'sea', 'inherited'}, inventory.biomes('child', palettes))
        self.assertEqual({'new', 'sea'}, inventory.biomes('empty', palettes))

    def test_every_authored_region_and_dimension_has_a_selection(self):
        regions = load('world_regions').registry()
        qa = load('world_test')
        self.assertEqual(83, sum(r['kind']=='authored_region' for r in qa.selected(regions, 'minecraft:overworld')))
        self.assertEqual(0,sum(r['kind']=='procedural_archetype' for r in regions))
        with self.assertRaises(ValueError):qa.selected(regions,'endless')
        self.assertEqual(1,len(qa.selected(regions,'ocean_wrap')))
        self.assertEqual(84, len(qa.selected(regions, 'minecraft:overworld')))
        self.assertEqual(5, len(qa.selected(regions, 'skycraft:coldharbour')))
        self.assertEqual(6, len(qa.selected(regions, 'sovngarde')))
        self.assertEqual(7, len(qa.selected(regions, 'shivering_isles')))
        dimensions = {r['dimension'] for r in regions}
        self.assertEqual(8, len(dimensions))
        self.assertEqual(qa.selected(regions, 'deadlands'), qa.selected(regions, 'minecraft:the_nether'))
        jail = qa.selected(regions, 'skycraft:jail')[0]
        self.assertIn('weather', jail['excludedSystems'])
        self.assertNotIn('magic', jail['excludedSystems'])
        with self.assertRaises(ValueError): qa.selected(regions, 'skycraft:unregistered')


if __name__ == '__main__': unittest.main()
