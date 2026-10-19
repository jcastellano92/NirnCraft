import copy
from pathlib import Path
import struct
import tempfile
import unittest
import zlib
import legacy_probe_263 as probe


def baseline():
    store = {"present": True, "rows": 1, "native_rows": 0, "items": 300,
             "items_by_id": {"minecraft:arrow": 300},
             "ordered_rows": [{"id": "minecraft:arrow", "count": 300,
                               "marker": {"migration_probe": "test", "probe_value": 73},
                               "item_state": {"damage": 0, "enchantments": {}}}]}
    return {"world": "legacy", "statistics": {"nonempty_overflow_attachments": 1, "nonempty_placed_attachments": 1},
            "probes": [{"region": "region/r.0.0.mca", "chunk": [0, 0], "data_version": 3465,
                        "placed_positions": [1, 2, 3], "block_entities": [{"position": [1, 70, 1],
                        "block_entity": "minecraft:barrel", "overflow": store}]}]}


class LegacyProbeTest(unittest.TestCase):
    def native(self):
        result = baseline()
        result["world"] = "native-scratch"
        result["probes"][0]["data_version"] = 5000
        result["probes"][0]["block_entities"][0]["overflow"]["native_rows"] = 1
        return result

    def test_counts_markers_and_position_sets_survive_a_layout_change(self):
        self.assertTrue(probe.compare(baseline(), self.native())["passed"])
        self.assertEqual(2, probe.compare(baseline(), self.native())["nonempty_before"])

    def test_an_untouched_copy_cannot_claim_native_load_save_acceptance(self):
        self.assertFalse(probe.compare(baseline(), baseline())["passed"])

    def test_native_dimension_relocation_keeps_legacy_probe_coordinates(self):
        for legacy, native, dimension in (
            ("region", "dimensions/minecraft/overworld/region", "minecraft:overworld"),
            ("DIM-1/region", "dimensions/minecraft/the_nether/region", "minecraft:the_nether"),
            ("DIM1/region", "dimensions/minecraft/the_end/region", "minecraft:the_end"),
            ("dimensions/nirncraft/coldharbour/region", "dimensions/nirncraft/coldharbour/region", "nirncraft:coldharbour"),
        ):
            with self.subTest(dimension=dimension), tempfile.TemporaryDirectory() as directory:
                world = Path(directory)
                region = world / native / "r.0.0.mca"
                region.parent.mkdir(parents=True)
                chunk = probe.nbt.compound(DataVersion=probe.nbt.Node(3, struct.pack(">i", 5000)))
                packed = zlib.compress(probe.nbt.write_nbt(b"", chunk))
                record = struct.pack(">I", len(packed) + 1) + bytes([2]) + packed
                header = bytearray(8192)
                struct.pack_into(">I", header, 0, (2 << 8) | 1)
                region.write_bytes(header + record + bytes(4096 - len(record)))
                result = probe.scan(world, {"probes": [{"region": legacy + "/r.0.0.mca", "chunk": [0, 0]}]})
                self.assertEqual(1, result["statistics"]["chunks_read"])
                self.assertEqual(legacy + "/r.0.0.mca", result["probes"][0]["region"])
                self.assertEqual(native + "/r.0.0.mca", result["region_inputs"][0]["physical_region"])
                self.assertEqual(dimension, result["probes"][0]["dimension"])

    def test_only_empty_native4885_bed_records_with_preserved_blocks_may_disappear(self):
        before = baseline()
        current = self.native()
        bed = {"position": [2, 70, 1], "block_entity": "minecraft:bed", "additional_fields": [],
               "overflow": {"present": False, "items": 0}}
        state = {"id": "minecraft:red_bed", "properties": {"facing": "north", "occupied": "false", "part": "foot"}}
        before["probes"][0]["block_entities"].append(bed)
        before["probes"][0]["sampled_blocks"] = [{"position": bed["position"], "state": state}]
        current["probes"][0]["sampled_blocks"] = copy.deepcopy(before["probes"][0]["sampled_blocks"])
        accepted = probe.compare(before, current)
        self.assertTrue(accepted["passed"])
        self.assertEqual(1, len(accepted["native_schema_transitions"]))
        for mutate in (
            lambda b, a: b["probes"][0]["block_entities"][-1]["additional_fields"].append("CustomName"),
            lambda b, a: b["probes"][0]["block_entities"][-1]["overflow"].update({"present": True}),
            lambda b, a: b["probes"][0]["block_entities"][-1]["overflow"].update({"items": 7}),
            lambda b, a: a["probes"][0]["sampled_blocks"][0]["state"].update({"id": "minecraft:air"}),
            lambda b, a: a["probes"][0]["sampled_blocks"].clear(),
            lambda b, a: a["probes"][0].update({"data_version": 4884}),
        ):
            with self.subTest(mutate=mutate):
                changed_before, changed_after = copy.deepcopy(before), copy.deepcopy(current)
                mutate(changed_before, changed_after)
                self.assertFalse(probe.compare(changed_before, changed_after)["passed"])

    def test_missing_or_changed_payloads_fail_even_when_the_record_count_matches(self):
        for mutate in (
            lambda p: p["placed_positions"].__setitem__(0, 4),
            lambda p: p["block_entities"][0]["overflow"]["ordered_rows"][0]["marker"].clear(),
            lambda p: p["block_entities"][0]["overflow"]["items_by_id"].update({"minecraft:arrow": 299}),
            lambda p: p["block_entities"][0]["overflow"]["ordered_rows"][0]["item_state"].update({"damage": 17}),
            lambda p: p["block_entities"][0]["overflow"].update({"native_rows": 0}),
            lambda p: p["block_entities"].clear(),
        ):
            with self.subTest(mutate=mutate):
                actual = self.native()
                mutate(actual["probes"][0])
                self.assertFalse(probe.compare(baseline(), actual)["passed"])


if __name__ == "__main__":
    unittest.main()
