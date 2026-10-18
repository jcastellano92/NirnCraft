"""Semantic migration checks: source content survives native 26.3 codecs and paths."""
import ast
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

import data_format_263 as data

sys.path.insert(0, str(Path(__file__).parent / "worldgen"))
import format_263 as world


class NativeDataMigration(unittest.TestCase):
    def test_custom_dungeon_palette_ids_keep_properties_weights_and_source(self):
        original = {"block": ["minecraft:chain[axis=y]@4", "minecraft:grass@2", "minecraft:grass_block*3"],
                    "description": "minecraft:grass stays here as prose", "rock": "rock@12"}
        saved = copy.deepcopy(original)
        path, converted = data.prepare("/repo/resources/skycraft/dungeons/caverns/profiles/test.json", original)
        self.assertEqual(original, saved)
        self.assertEqual(converted["block"], ["minecraft:iron_chain[axis=y]@4", "minecraft:short_grass@2", "minecraft:grass_block*3"])
        self.assertEqual(converted["description"], original["description"])
        self.assertEqual(data.prepare(path, converted), (path, converted))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "data"
            root.mkdir()
            profile = root.parent / "skycraft/dungeons/caverns/profiles/test.json"
            profile.parent.mkdir(parents=True)
            profile.write_text(json.dumps(original), encoding="utf-8")
            self.assertGreaterEqual(data.upgrade_resources(root)["converted"], 1)
            self.assertEqual(json.loads(profile.read_text(encoding="utf-8")), converted)
            self.assertEqual(data.upgrade_resources(root)["converted"], 0)

    def test_recipe_ingredients_and_counts(self):
        old = {"type": "minecraft:crafting_shaped", "pattern": ["AB"],
               "key": {"A": {"item": "skycraft:ebony_ingot"}, "B": {"tag": "c:rods/wooden"}},
               "result": {"item": "skycraft:ebony_arrows", "count": 24}}
        saved = copy.deepcopy(old)
        converted = data.recipe(old)
        self.assertEqual(old, saved)
        self.assertEqual(converted["key"], {"A": "skycraft:ebony_ingot", "B": "#c:rods/wooden"})
        self.assertEqual(converted["result"], {"id": "skycraft:ebony_arrows", "count": 24})
        self.assertEqual(data.recipe(converted), converted)

    def test_intentionally_blocked_recipe_stays_blocked(self):
        old = {"type": "minecraft:crafting_shapeless", "conditions": [{"type": "forge:false"}],
               "ingredients": [{"item": "minecraft:diamond"}], "result": {"item": "minecraft:enchanting_table"}}
        converted = data.recipe(old)
        self.assertNotIn("conditions", converted)
        self.assertEqual(converted["neoforge:conditions"], [{"type": "neoforge:false"}])
        self.assertEqual(data.recipe(converted), converted)

    def test_disabled_recipe_filter_can_override_later_loader_pack_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            resources = Path(directory)
            root = resources / "data"
            source = root / "minecraft/recipes/enchanting_table.json"
            source.parent.mkdir(parents=True)
            source.write_text(json.dumps({"type": "minecraft:crafting_shaped", "conditions": [{"type": "forge:false"}]}), encoding="utf-8")
            pack = resources / "pack.mcmeta"
            metadata = {"pack": {"description": "Keep this", "min_format": [97, 1], "max_format": [121, 0]},
                        "filter": {"block": [{"namespace": "other", "path": "retained"}]}}
            pack.write_text(json.dumps(metadata), encoding="utf-8")
            data.upgrade_resources(root)
            self.assertFalse(source.exists())
            self.assertFalse((root / "minecraft/recipe/enchanting_table.json").exists())
            self.assertTrue((root / "minecraft/skycraft_legacy/disabled_recipes/enchanting_table.json").exists())
            actual = json.loads(pack.read_text(encoding="utf-8"))
            self.assertEqual(actual["pack"], metadata["pack"])
            filters = [{"namespace": "minecraft", "path": "recipe/enchanting_table[.]json"},
                       {"namespace": "minecraft", "path": "advancement/recipes/decorations/enchanting_table[.]json"}]
            self.assertEqual(actual["filter"]["block"], metadata["filter"]["block"] + filters)
            top_pack = json.loads((resources / "resourcepacks/recipe_rules/pack.mcmeta").read_text(encoding="utf-8"))
            self.assertEqual(top_pack["filter"]["block"], filters)
            self.assertEqual(data.upgrade_resources(root), {"converted": 0, "moved": 0})

    def test_loot_keeps_order_weights_and_custom_metadata(self):
        old = {"type": "minecraft:loot_table", "name": "skycraft:parts/treasure", "weight": 7,
               "conditions": [{"condition": "minecraft:killed_by_player"}, {"condition": "skycraft:loot_level", "min": 8}],
               "functions": [{"function": "minecraft:set_count", "count": 3},
                             {"function": "minecraft:set_nbt", "tag": '{book:"the_hist",skycraft_value:25}'}]}
        converted = data.loot(old)
        self.assertEqual(converted["value"], "skycraft:parts/treasure")
        self.assertEqual(converted["weight"], 7)
        self.assertEqual([p["type"] for p in converted["condition"]["terms"]],
                         ["minecraft:killed_by_player", "skycraft:loot_level"])
        self.assertEqual([p["type"] for p in converted["modifier"]],
                         ["minecraft:set_count", "minecraft:set_custom_data"])
        self.assertEqual(converted["modifier"][1]["tag"], old["functions"][1]["tag"])
        self.assertEqual(data.loot(converted), converted)

    def test_looting_probability_at_every_level(self):
        for chance, multiplier in ((0.025, 0.01), (0.9, 0.05), (0.4, 0.05)):
            converted = data.loot({"condition": "minecraft:random_chance_with_looting",
                                   "chance": chance, "looting_multiplier": multiplier})
            for level in range(10):
                native = converted["unenchanted_chance"] if level == 0 else (
                    converted["enchanted_chance"]["base"] + (level - 1) * converted["enchanted_chance"]["per_level_above_first"])
                self.assertAlmostEqual(native, chance + multiplier * level)

    def test_item_and_entity_predicate_constraints_survive(self):
        converted = data.loot({"condition": "minecraft:match_tool", "predicate": {
            "enchantments": [{"enchantment": "minecraft:silk_touch", "levels": {"min": 1}}]}})
        constraint = converted["predicate"]["predicates"]["minecraft:enchantments"][0]
        self.assertEqual(constraint, {"enchantments": "minecraft:silk_touch", "levels": {"min": 1}})
        converted = data.loot({"condition": "minecraft:entity_properties", "entity": "this",
                               "predicate": {"flags": {"is_on_fire": True}, "nbt": "{Male:1b}"}})
        self.assertEqual(converted["predicate"], {"minecraft:flags": {"is_on_fire": True}, "minecraft:nbt": "{Male:1b}"})

    def test_resource_moves_preserve_ids_and_merge_tags(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "data"
            old = root / "skycraft/tags/items/ore.json"
            native = root / "skycraft/tags/item/ore.json"
            old.parent.mkdir(parents=True)
            native.parent.mkdir(parents=True)
            old.write_text(json.dumps({"values": ["skycraft:ebony_ore"]}), encoding="utf-8")
            native.write_text(json.dumps({"values": ["skycraft:moonstone_ore"]}), encoding="utf-8")
            self.assertEqual(data.upgrade_resources(root)["moved"], 1)
            self.assertEqual(json.loads(native.read_text(encoding="utf-8"))["values"],
                             ["skycraft:moonstone_ore", "skycraft:ebony_ore"])
            self.assertFalse(old.exists())
            self.assertEqual(data.upgrade_resources(root), {"converted": 0, "moved": 0})

    def test_native_global_modifiers_keep_authoring_order_without_invalid_list_resource(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "data"
            old = root / "forge/loot_modifiers/global_loot_modifiers.json"
            old.parent.mkdir(parents=True)
            old.write_text(json.dumps({"replace": False, "entries": ["skycraft:second", "skycraft:first"]}), encoding="utf-8")
            for name in ("first", "second"):
                path = root / "skycraft/loot_modifiers" / (name + ".json")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps({"type": "skycraft:add_table", "table": "skycraft:" + name}), encoding="utf-8")
            data.upgrade_resources(root)
            self.assertFalse(old.exists())
            archived = root / "neoforge/skycraft_legacy/global_loot_modifiers.json"
            self.assertEqual(json.loads(archived.read_text(encoding="utf-8"))["entries"], ["skycraft:second", "skycraft:first"])
            priorities = {name: json.loads((root / "skycraft/loot_modifiers" / (name + ".json")).read_text(encoding="utf-8"))["priority"]
                          for name in ("first", "second")}
            self.assertGreater(priorities["second"], priorities["first"])
            self.assertEqual(data.upgrade_resources(root), {"converted": 0, "moved": 0})

    def test_common_tag_bridges_keep_custom_and_native_members_without_cycle(self):
        old = {"replace": False, "values": ["skycraft:steel_ingot", {"id": "skycraft:optional_metal", "required": False}]}
        _, converted = data.prepare("/repo/data/forge/tags/items/ingots.json", old)
        self.assertEqual(converted["values"][:2], old["values"])
        self.assertIn("#c:ingots/iron", converted["values"])
        self.assertNotIn("#c:ingots", converted["values"])
        self.assertEqual(data.common_tag("/repo/data/forge/tags/item/ingots.json", converted), converted)
        self.assertEqual(data.unique([old["values"][1], old["values"][1]]), [old["values"][1]])

    def test_native_feature_move_retains_custom_configuration(self):
        old = {"type": "skycraft:river_site", "config": {"salt": 41, "spacing": 128}}
        path, converted = world.prepare("/repo/data/skycraft/worldgen/configured_feature/river_site.json", old)
        self.assertTrue(Path(path).as_posix().endswith("worldgen/feature/river_site.json"))
        self.assertEqual(converted, old)

    def test_runtime_loot_block_predicate_retains_identity_and_exact_state(self):
        old = {"condition": "minecraft:block_state_property", "block": "skycraft:cabbage_crop", "properties": {"age": "7"}}
        converted = data.loot(old)
        self.assertEqual(converted, {"type": "minecraft:match_block", "blocks": "skycraft:cabbage_crop", "state": {"age": "7"}})
        self.assertEqual(data.loot(converted), converted)
        self.assertEqual(old["properties"], {"age": "7"})

    def test_runtime_worldgen_native_holders_spreads_and_spawn_ranges(self):
        spread = {"type": "minecraft:uniform", "min_inclusive": -2, "max_inclusive": 3}
        offset = world.tree({"type": "minecraft:random_offset", "xz_spread": spread, "y_spread": 1})
        self.assertEqual(offset, {"type": "minecraft:offset", "x": spread, "y": 1, "z": spread})
        self.assertEqual(world.tree({"type": "minecraft:soil_beneath_tree"}), "minecraft:soil_beneath_tree")
        self.assertEqual(world.tree({"type": "minecraft:blend_density", "argument": "skycraft:tamriel/sloped"}),
                         {"type": "minecraft:blend_density", "input": "skycraft:tamriel/sloped"})
        spawn = world.tree({"type": "skycraft:dwarven_spider", "weight": 10, "minCount": 1, "maxCount": 2})
        self.assertEqual(spawn, {"type": "skycraft:dwarven_spider", "weight": 10,
                                "count": {"type": "minecraft:uniform", "min_inclusive": 1, "max_inclusive": 2}})
        for value in (offset, spawn):
            self.assertEqual(world.tree(value), value)

    def test_moon_cloud_renderers_and_clocks(self):
        old = {"effects": "skycraft:deadlands", "fixed_time": 13200, "bed_works": False,
               "natural": False, "ultrawarm": True, "piglin_safe": True, "has_raids": False}
        converted = world.dimension_type(old, Path("the_nether.json"))
        self.assertEqual(converted["default_clock"], "skycraft:fixed/the_nether")
        self.assertTrue(converted["has_fixed_time"])
        self.assertEqual(converted["attributes"]["neoforge:custom_skybox"], "skycraft:deadlands")
        self.assertEqual(converted["attributes"]["neoforge:custom_clouds"], "skycraft:deadlands")
        self.assertTrue(converted["attributes"]["minecraft:gameplay/water_evaporates"])
        self.assertFalse(converted["attributes"]["minecraft:gameplay/piglins_zombify"])
        self.assertEqual(world.dimension_type(converted, Path("the_nether.json")), converted)
        missing_flag = copy.deepcopy(converted)
        missing_flag.pop("has_fixed_time")
        self.assertEqual(world.dimension_type(missing_flag, Path("the_nether.json")), converted)
        ordinary = world.dimension_type({"effects": "minecraft:overworld", "bed_works": True}, Path("overworld.json"))
        self.assertFalse(ordinary.get("has_fixed_time", False))

    def test_custom_realm_timelines_keep_authored_biome_fog_and_lighting(self):
        for name in ("the_nether", "the_end", "apocrypha", "soul_cairn", "coldharbour"):
            clock = "skycraft:fixed/" + name
            day = world.fixed_timeline(clock, "day")
            self.assertEqual(day["clock"], clock)
            self.assertNotIn("minecraft:visual/fog_color", day["tracks"])
            self.assertNotIn("minecraft:visual/sunrise_sunset_color", day["tracks"])
            self.assertIn("minecraft:visual/sky_light_factor", day["tracks"])
            self.assertIn("minecraft:gameplay/sky_light_level", day["tracks"])
            moon = world.fixed_timeline(clock, "moon")
            self.assertIn("minecraft:visual/moon_phase", moon["tracks"])
        jail = world.fixed_timeline("skycraft:fixed/jail", "day")
        self.assertIn("minecraft:visual/fog_color", jail["tracks"])
        self.assertIn("minecraft:visual/sunrise_sunset_color", jail["tracks"])

    def test_all_eight_legacy_dimension_behavior_fields_are_mapped_explicitly(self):
        fixture = Path(__file__).resolve().parents[1] / "mod/src/test/resources/worldgen/dimension-types-legacy-1.20.1.json"
        dimensions = json.loads(fixture.read_text(encoding="utf-8"))
        self.assertEqual(len(dimensions), 8)
        for identifier, old in dimensions.items():
            converted = world.dimension_type(old, Path(identifier.split(":", 1)[1] + ".json"))
            attributes = converted["attributes"]
            for field, expected in {"water_evaporates": old["ultrawarm"], "fast_lava": old["ultrawarm"],
                    "nether_portal_spawns_piglin": old["natural"], "can_start_raid": old["has_raids"],
                    "piglins_zombify": not old["piglin_safe"], "respawn_anchor_works": old["respawn_anchor_works"]}.items():
                self.assertEqual(attributes["minecraft:gameplay/" + field], expected, (identifier, field))
            drip = "minecraft:dripping_dripstone_lava" if old["ultrawarm"] else "minecraft:dripping_dripstone_water"
            self.assertEqual(attributes["minecraft:visual/default_dripstone_particle"], {"type": drip})
            self.assertEqual(converted["has_fixed_time"], "fixed_time" in old)
            beds = attributes["minecraft:gameplay/bed_rule"]
            self.assertEqual(beds["can_sleep"], "when_dark" if old["bed_works"] and old["natural"] else "never")
            self.assertEqual(beds["can_set_spawn"], "always" if old["bed_works"] and old["natural"] else "never")
            self.assertEqual(beds.get("destroy_on_use", False), not old["bed_works"])
            self.assertEqual(world.dimension_type(converted, Path(identifier.split(":", 1)[1] + ".json")), converted)

    def test_actual_generator_writers_use_native_boundary(self):
        # Execute the real, isolated writer definitions without rerunning generators
        # which would intentionally overwrite authored visual assets and content.
        root = Path(__file__).resolve().parent
        writers = [("economy_data.py", "write"), ("loot_data.py", "write"),
                   ("seas_data.py", "write"), ("explore/gen_explore_data.py", "write"),
                   ("textures/arsenal.py", "write_json"), ("textures/fire.py", "write_json"),
                   ("textures/gear.py", "write_json"), ("textures/lore.py", "write_json"),
                   ("textures/magic.py", "write_json"), ("textures/realms.py", "dump"),
                   ("textures/tamriel.py", "dump"), ("worldgen/caverns.py", "write")]
        with tempfile.TemporaryDirectory() as directory:
            for name, function in writers:
                with self.subTest(generator=name):
                    source = root / name
                    parsed = ast.parse(source.read_text(encoding="utf-8"))
                    definition = next(node for node in parsed.body if isinstance(node, ast.FunctionDef) and node.name == function)
                    module = ast.Module(body=[definition], type_ignores=[])
                    namespace = {"__file__": str(source), "Path": Path, "os": os, "json": json}
                    exec(compile(module, str(source), "exec"), namespace)
                    path = Path(directory) / name.replace("/", "_") / "data/skycraft/recipes/test.json"
                    namespace[function](path, {"type": "minecraft:smelting", "ingredient": {"item": "skycraft:ebony_ore"}, "result": "skycraft:ebony_ingot"})
                    native = path.parent.parent / "recipe/test.json"
                    self.assertEqual(json.loads(native.read_text(encoding="utf-8"))["result"], {"id": "skycraft:ebony_ingot"})
                    self.assertFalse(path.exists())


if __name__ == "__main__":
    unittest.main()
