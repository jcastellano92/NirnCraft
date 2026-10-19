import copy
import gzip
import hashlib
import importlib.util
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zlib
import re

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("legacy_attachments_263", HERE / "legacy_attachments_263.py")
m = importlib.util.module_from_spec(spec); sys.modules[spec.name] = m; spec.loader.exec_module(m)

def nbt_caps():
    # Modified UTF-8 and floating point payloads stay raw, including unpaired surrogates and NaN bits.
    data = m.Node(10, [(b"race", m.Node(8, b"nord")), (b"name", m.Node(8, b"A\xc0\x80\xed\xa0\x80")),
                       (b"nan", m.Node(5, bytes.fromhex("7fc01234")))])
    overflow = m.Node(10, [(b"items", m.Node(9, (10, [m.compound(id=m.Node(8, b"minecraft:diamond"), Count=m.Node(1, b"\x01"))])))])
    positions = m.Node(12, struct.pack(">iqq", 2, 7, -991))
    caps = m.Node(10, [(b"nirncraft:player_data", data), (b"nirncraft:overflow", overflow), (b"nirncraft:placed_blocks", positions), (b"other_mod:untouched", m.Node(8, b"opaque"))])
    return m.Node(10, [(b"DataVersion", m.Node(3, struct.pack(">i", 3465))), (b"ForgeCaps", caps)])

class LegacyAttachmentsTest(unittest.TestCase):
    def test_lossless_typed_nbt_round_trip(self):
        root = nbt_caps(); raw = m.write_nbt(b"root", root)
        self.assertEqual(raw, m.write_nbt(*m.read_nbt(raw)))
        self.assertEqual(root, m.read_nbt(raw)[1])

    def test_all_attachment_payloads_preserve_legacy_bytes_and_are_idempotent(self):
        root = nbt_caps(); before = m.write_nbt(b"", root); caps = copy.deepcopy(m.child(root, b"ForgeCaps")); counts = {}
        m.convert(root, counts)
        self.assertEqual({key.decode(): 1 for key in m.OWNED}, counts)
        self.assertEqual(caps, m.child(root, b"ForgeCaps"))
        native = m.child(root, m.ATTACHMENTS)
        for key, (_, wrapper) in m.OWNED.items():
            self.assertEqual(m.child(caps, key), m.child(m.child(native, key), wrapper))
        migrated = m.write_nbt(b"", root); m.convert(root, counts)
        self.assertEqual(migrated, m.write_nbt(b"", root)); self.assertNotEqual(before, migrated)
        self.assertEqual(3, sum(counts.values()))

    def test_existing_native_data_wins(self):
        root = nbt_caps(); native = m.Node(10, [(b"nirncraft:player_data", m.compound(data=m.compound()))]); m.put(root, m.ATTACHMENTS, native)
        original = copy.deepcopy(m.child(native, b"nirncraft:player_data")); counts = {}; m.convert(root, counts)
        self.assertEqual(original, m.child(native, b"nirncraft:player_data")); self.assertNotIn("nirncraft:player_data", counts)

    def test_region_inline_and_external_chunks_keep_unknown_fields(self):
        for compression, external in [(1, False), (2, False), (3, False), (2, True)]:
            with self.subTest(compression=compression, external=external), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "r.0.0.mca"; raw = m.write_nbt(b"", nbt_caps())
                packed = gzip.compress(raw) if compression == 1 else zlib.compress(raw) if compression == 2 else raw
                payload = struct.pack(">I", 1 if external else len(packed)+1) + bytes([compression | (128 if external else 0)]) + (b"" if external else packed)
                header = bytearray(8192); struct.pack_into(">I", header, 0, (2 << 8) | 1); header[4096:4100] = b"\x12\x34\x56\x78"
                path.write_bytes(header + payload + bytes(4096-len(payload)))
                if external: (Path(tmp)/"c.0.0.mcc").write_bytes(packed)
                counts = {}; self.assertTrue(m.region(path, counts)); rebuilt = path.read_bytes()
                self.assertEqual(b"\x12\x34\x56\x78", rebuilt[4096:4100]); self.assertEqual(3, sum(counts.values()))
                offset = (struct.unpack_from(">I", rebuilt, 0)[0] >> 8)*4096; length = struct.unpack_from(">I", rebuilt, offset)[0]
                tree = m.read_nbt(m.unpack(rebuilt[offset+5:offset+4+length], compression))[1]
                self.assertIsNotNone(m.child(tree, m.ATTACHMENTS)); self.assertFalse(m.region(path, counts))

    def test_world_copy_preserves_source_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp)/"original"; source.mkdir(); target = Path(tmp)/"converted"
            data = gzip.compress(m.write_nbt(b"", m.compound(Player=nbt_caps())))
            (source/"level.dat").write_bytes(data); before = hashlib.sha256(data).hexdigest()
            report = m.migrate(source, target)
            self.assertFalse(report["source_modified"]); self.assertEqual(3, sum(report["attachments"].values()))
            self.assertEqual(before, hashlib.sha256((source/"level.dat").read_bytes()).hexdigest())
            self.assertTrue((target/"nirncraft-26.3-migration.json").exists())
            with self.assertRaises(ValueError): m.migrate(source, target)
            with self.assertRaises(ValueError): m.migrate(source, source/"nested")

    def test_malformed_data_fails_closed(self):
        with self.assertRaises(ValueError): m.read_nbt(b"\x0a\x00\x00\x0c\x00\x01x\x7f\xff\xff\xff")
        with self.assertRaises(ValueError): m.unpack(zlib.compress(b"x")[:-1], 2)
        with self.assertRaises(ValueError): m.unpack(b"unknown", 4)

    def test_empty_native_region_is_valid_but_partial_header_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/"r.0.0.mca"; path.write_bytes(b"")
            self.assertFalse(m.region(path,{})); self.assertEqual(b"",path.read_bytes())
            path.write_bytes(b"\0"*4096)
            with self.assertRaises(ValueError): m.region(path,{})

    def test_world_config_override_copy_preserves_source_and_native_precedence(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp)/"original"; source.mkdir(); target = Path(tmp)/"converted"
            (source/"level.dat").write_bytes(gzip.compress(m.write_nbt(b"", m.compound())))
            (source/"serverconfig").mkdir(); (source/"syncedconfig").mkdir()
            for name in m.SYNCED_CONFIGS:
                (source/"serverconfig"/name).write_bytes(b"# authored legacy override\r\nvalue = 7\r\n")
            native = "nirncraft-ocean-server.toml"
            (source/"syncedconfig"/native).write_bytes(b"# native override wins\nvalue = 9\n")
            (source/"serverconfig/other-mod-server.toml").write_text("untouched = true\n")
            before = {str(p.relative_to(source)): p.read_bytes() for p in source.rglob("*") if p.is_file()}
            report = m.migrate(source, target)
            self.assertEqual(2, len(report["synced_configs"]["copied"]))
            self.assertEqual([str(Path("syncedconfig")/native)], report["synced_configs"]["existing_native_overrides_preserved"])
            self.assertEqual(before, {str(p.relative_to(source)): p.read_bytes() for p in source.rglob("*") if p.is_file()})
            self.assertEqual((source/"syncedconfig"/native).read_bytes(), (target/"syncedconfig"/native).read_bytes())
            for name in m.SYNCED_CONFIGS - {native}:
                self.assertEqual((source/"serverconfig"/name).read_bytes(), (target/"syncedconfig"/name).read_bytes())
            self.assertFalse((target/"syncedconfig/other-mod-server.toml").exists())
            self.assertEqual([], m.migrate_configs(target)["copied"])

    def test_config_migration_covers_every_explicit_synced_registration(self):
        sources = HERE.parents[1]/"mod/src/main/java/com/nirncraft"
        registered = set()
        for path in sources.rglob("*.java"):
            registered.update(re.findall(r"config\(ModConfig\.Type\.SYNCED,\s*\w+\.\w+\s*,\s*\"([^\"]+)\"", path.read_text(encoding="utf-8")))
            registered.update(re.findall(r"config\(ModConfig\.Type\.SYNCED,\s*SPEC\s*,\s*\"([^\"]+)\"", path.read_text(encoding="utf-8")))
        self.assertEqual(m.SYNCED_CONFIGS, registered)

    def test_survival_rule_is_typed_and_retains_existing_native_value(self):
        for value in (b"true", b"false"):
            with self.subTest(value=value):
                rules = m.compound(nirncraftSurvivalMode=m.Node(8,value), doDaylightCycle=m.Node(8,b"false"))
                root = m.compound(Data=m.compound(GameRules=rules)); counts = {}
                packed = m.migrate_bytes(m.write_nbt(b"",root),3,counts)
                rebuilt = m.read_nbt(packed)[1]
                new_rules = m.child(m.child(rebuilt,b"Data"),b"GameRules")
                self.assertEqual(m.Node(1,b"\x01" if value == b"true" else b"\x00"),m.child(new_rules,b"nirncraft:survival_mode"))
                self.assertEqual(m.Node(8,b"false"),m.child(new_rules,b"doDaylightCycle"))
                self.assertIsNone(m.child(new_rules,b"nirncraftSurvivalMode"))
                self.assertEqual(1,counts["game_rule:nirncraft:survival_mode"])
                self.assertIsNone(m.migrate_bytes(packed,3,counts))
        rules = m.compound(nirncraftSurvivalMode=m.Node(8,b"false"))
        native = m.Node(1,b"\x01"); m.put(rules,b"nirncraft:survival_mode",native)
        m.convert_survival_rule(m.compound(Data=m.compound(GameRules=rules)),{})
        self.assertEqual(native,m.child(rules,b"nirncraft:survival_mode"))
        with self.assertRaises(ValueError):
            m.convert_survival_rule(m.compound(Data=m.compound(GameRules=m.compound(nirncraftSurvivalMode=m.Node(8,b"invalid")))),{})

    def test_real_legacy_random_sequences_keep_all_states_and_exact_schema_boundary(self):
        fixtures = HERE.parents[1]/"mod/src/test/resources/migration"
        raw = (fixtures/"random-sequences-1.20.1-original.dat").read_bytes()
        name, original = m.read_nbt(gzip.decompress(raw))
        states = copy.deepcopy(m.child(original,b"data")); before = m.write_nbt(name, original)
        m.put(original,b"unrelated_root", m.Node(8,b"opaque"))
        counts = {}; m.convert_random_sequences(original,counts)
        converted = m.child(original,b"data")
        self.assertEqual(states,m.child(converted,b"sequences"))
        self.assertEqual(15,len(states.value))
        self.assertEqual(m.Node(3,struct.pack(">i",3565)),m.child(original,b"DataVersion"))
        self.assertEqual(m.Node(3,struct.pack(">i",0)),m.child(converted,b"salt"))
        self.assertEqual(m.Node(1,b"\x01"),m.child(converted,b"include_world_seed"))
        self.assertEqual(m.Node(1,b"\x01"),m.child(converted,b"include_sequence_id"))
        self.assertEqual(m.Node(8,b"opaque"),m.child(original,b"unrelated_root"))
        snapshot = m.write_nbt(name,original); m.convert_random_sequences(original,counts)
        self.assertEqual(snapshot,m.write_nbt(name,original)); self.assertEqual(1,sum(counts.values()))
        packed = m.migrate_bytes(raw,1,{},random_sequences=True)
        self.assertEqual(gzip.decompress(packed),gzip.decompress((fixtures/"random-sequences-1.20.1-converted-3565.dat").read_bytes()))
        self.assertEqual(before,m.write_nbt(*m.read_nbt(gzip.decompress(raw))),"Original source fixture remains unchanged")

    def test_invalid_legacy_random_state_fails_without_resetting_it(self):
        root = m.compound(DataVersion=m.Node(3,struct.pack(">i",3465)),data=m.compound(broken=m.Node(8,b"opaque")))
        before=m.write_nbt(b"",root)
        with self.assertRaises(ValueError): m.convert_random_sequences(root,{})
        self.assertEqual(before,m.write_nbt(b"",root))

if __name__ == "__main__": unittest.main()
