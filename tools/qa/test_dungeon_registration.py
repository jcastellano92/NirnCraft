"""Checks paired placement/structure eligibility for deliberately supported dungeon variants."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORLDGEN = ROOT / 'mod/src/main/resources/data/skycraft/worldgen'


def matches(rule, place):
    return place['type'] in rule['types'] and (not rule.get('variants') or place['variant'] in rule['variants'])


class DungeonRegistrationTest(unittest.TestCase):
    def test_supported_variants_have_matching_placement_and_emitter(self):
        wanted = {'great_lift': [('dungeon_fixed', 'mzark')],
                  'fort': [('fort', 'fort'), ('dungeon_fixed', 'fort')],
                  'fort_akaviri': [('fort', 'akaviri_fort'), ('dungeon_fixed', 'akaviri_fort')],
                  'scorpion_den': [('dungeon_fixed', 'scorpion_den')],
                  'breton_keep_ruin': [('fort', 'breton_keep_ruin'), ('dungeon_fixed', 'breton_keep_ruin')]}
        for name, identities in wanted.items():
            with self.subTest(name=name):
                structure = json.loads((WORLDGEN / 'structure/site' / (name + '.json')).read_text())
                placement = json.loads((WORLDGEN / 'structure_set/site' / (name + '.json')).read_text())
                self.assertEqual(['skycraft:site/' + name], [r['structure'] for r in placement['structures']])
                for kind, variant in identities:
                    place = {'type': kind, 'variant': variant}
                    self.assertTrue(matches(structure['place'], place), place)
                    self.assertTrue(matches(placement['placement'], place), place)

    def test_added_variants_keep_architecture_palette_and_roster_in_the_paired_filters(self):
        expected = {
            'bosmer_ossuary': ('ruin', 'bosmer', None),
            'cultist_lair': ('necromancer_lair', 'daedric', None),
            'dunmer_stronghold': ('fort', 'velothi', None),
            'elven_ruin': ('ruin', 'altmer', None),
            'imperial_prison_fort': ('fort', 'imperial', None),
            'orichalc_ruin': ('ruin', 'redguard', None),
            'sandstone_tomb': ('ruin', 'khajiit', None),
            'tsaesci_fortress': ('fort', 'akaviri', None),
            'altmer_sky_spire': ('mage_tower', 'altmer', None),
            'velothi_tower': ('watchtower', 'velothi', None),
            'hound_den': ('cave', 'bosmer', 1),
            'root_hollow': ('cave', 'bosmer', 6),
            'khajiit_shrine': ('ruin', 'khajiit', None),
            'kamal_ice_vault': ('ruin', 'ice', None),
        }
        for variant, (theme, kit, flavor) in expected.items():
            with self.subTest(variant=variant):
                structure = json.loads((WORLDGEN / 'structure/site' / (variant + '.json')).read_text())
                placement = json.loads((WORLDGEN / 'structure_set/site' / (variant + '.json')).read_text())
                place = {'type': 'dungeon_fixed', 'variant': variant}
                self.assertTrue(matches(structure['place'], place))
                self.assertTrue(matches(placement['placement'], place))
                self.assertEqual('skycraft:site/' + variant, placement['structures'][0]['structure'])
                self.assertEqual((theme, kit, flavor), (structure['theme'], structure['kit'], structure.get('flavor')))
                self.assertFalse(matches(placement['placement'], dict(place, variant='kwama_egg_mine')))
                self.assertFalse(matches(structure['place'], dict(place, variant='sea_cave')))

    def test_historical_placement_salts_are_preserved(self):
        expected = {'site/barrow': 718274001, 'wilds_underground': 718273501, 'wilds_surface': 718273502}
        for name, salt in expected.items():
            placement = json.loads((WORLDGEN / 'structure_set' / (name + '.json')).read_text())
            self.assertEqual(salt, placement['placement']['salt'])


if __name__ == '__main__': unittest.main()
