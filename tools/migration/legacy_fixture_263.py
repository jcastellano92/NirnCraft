"""Create a clearly labeled nonempty LEGACY fixture on new copies, then run the real converter.

Never changes the supplied source world or an existing destination. The selected generated
barrel and chunk already exist in the archived real world; only their legacy ForgeCaps gain
two authored test rows (300 total items) and three player-placed positions.
"""
from __future__ import annotations
import argparse, json, shutil, struct, zlib
from pathlib import Path
import legacy_attachments_263 as nbt
import legacy_probe_263 as probe

REGION = "region/r.-1.1.mca"
CHUNK = (-4, 62)
BARREL = (-53, 162, 994)
MARKER = "populated-legacy-20261018"


def scalar(kind, value):
    return nbt.Node(kind, struct.pack({1: ">b", 2: ">h", 3: ">i", 4: ">q"}[kind], value))


def text(value):
    return nbt.Node(8, value.encode())


def position(x, y, z):
    value = ((x & 0x3ffffff) << 38) | ((z & 0x3ffffff) << 12) | (y & 0xfff)
    return value - (1 << 64) if value >= 1 << 63 else value


def create(source, legacy, converted, report):
    source = source.resolve(strict=True)
    legacy, converted = legacy.resolve(), converted.resolve()
    for destination in (legacy, converted):
        if destination.exists() or destination == source or source in destination.parents or destination in source.parents:
            raise ValueError("Use new, disjoint fixture destination directories")
    with nbt.offline_lock(source):
        shutil.copytree(source, legacy)
    region = legacy / REGION
    raw = bytearray(region.read_bytes())
    index = (CHUNK[0] % 32) + (CHUNK[1] % 32) * 32
    location = struct.unpack_from(">I", raw, index * 4)[0]
    offset = (location >> 8) * 4096
    length = struct.unpack_from(">I", raw, offset)[0]
    compression = raw[offset + 4]
    if compression & 128:
        raise ValueError("This fixed fixture expects its known inline source chunk")
    name, chunk = nbt.read_nbt(nbt.unpack(raw[offset + 5:offset + 4 + length], compression))
    block_entities = nbt.child(chunk, b"block_entities")
    found = [be for be in block_entities.value[1]
             if tuple(probe.number(nbt.child(be, axis)) for axis in (b"x", b"y", b"z")) == BARREL]
    if len(found) != 1 or probe.string(nbt.child(found[0], b"id")) != "minecraft:barrel":
        raise ValueError("Archived real barrel fixture moved or changed")
    be = found[0]
    if probe.attachment(be, b"skycraft:overflow") or probe.attachment(chunk, b"skycraft:placed_blocks"):
        raise ValueError("Fixture must start from the original LEGACY archive, before native conversion")
    caps = nbt.child(be, b"ForgeCaps")
    if caps is None:
        raise ValueError("Archived barrel no longer has the real legacy capability")

    rows = []
    for item, count, kind in (("minecraft:arrow", 297, "arrows"), ("minecraft:iron_sword", 3, "worn_enchanted_swords")):
        custom = nbt.compound(migration_probe=text(MARKER), probe_kind=text(kind), probe_value=scalar(3, 73))
        if item.endswith("iron_sword"):
            nbt.put(custom, b"Damage", scalar(3, 17))
            nbt.put(custom, b"Enchantments", nbt.Node(9, (10, [nbt.compound(id=text("minecraft:sharpness"), lvl=scalar(2, 2))])))
        stack = nbt.compound(id=text(item), Count=scalar(1, 1), tag=custom)
        rows.append(nbt.compound(s=stack, n=scalar(3, count)))
    nbt.put(caps, b"skycraft:overflow", nbt.compound(v=scalar(3, 1), e=nbt.Node(9, (10, rows))))
    chunk_caps = nbt.child(chunk, b"ForgeCaps")
    if chunk_caps is None:
        chunk_caps = nbt.Node(10, [])
        nbt.put(chunk, b"ForgeCaps", chunk_caps)
    positions = [position(*BARREL), position(BARREL[0], BARREL[1] - 1, BARREL[2]), position(BARREL[0] - 1, BARREL[1], BARREL[2])]
    nbt.put(chunk_caps, b"skycraft:placed_blocks", nbt.Node(12, struct.pack(">i", len(positions)) + b"".join(struct.pack(">q", value) for value in positions)))
    payload = zlib.compress(nbt.write_nbt(name, chunk))
    record = struct.pack(">I", len(payload) + 1) + bytes([2]) + payload
    sectors = (len(record) + 4095) // 4096
    if sectors > 255:
        raise ValueError("Fixture unexpectedly exceeds inline region capacity")
    new_sector = len(raw) // 4096
    raw.extend(record + bytes(sectors * 4096 - len(record)))
    struct.pack_into(">I", raw, index * 4, (new_sector << 8) | sectors)
    region.write_bytes(raw)
    provenance = {"label": "SYNTHETIC NONEMPTY LEGACY ATTACHMENT FIXTURE on a real archived world copy",
                  "original_source": str(source), "source_modified": False, "region": REGION, "chunk": CHUNK,
                  "barrel": BARREL, "overflow_items": 300, "overflow_rows": 2, "placed_positions": sorted(positions),
                  "marker": MARKER, "data_version": "unchanged; actual native upgrader must still run",
                  "playerdata": "not fabricated; no original player payload exists; native inventory selftest covers real player attachment round trips"}
    (legacy / "skycraft-populated-legacy-fixture.json").write_text(json.dumps(provenance, indent=2) + "\n")
    nbt.migrate(legacy, converted)
    result = probe.scan(converted, {"probes": [{"region": REGION, "chunk": list(CHUNK)}]})
    result["fixture_provenance"] = provenance
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(result, indent=2) + "\n")
    return {"legacy": str(legacy), "converted": str(converted), "report": str(report), "fixture": provenance}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("new_legacy_fixture", type=Path)
    parser.add_argument("new_converted_fixture", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    print(json.dumps(create(args.source, args.new_legacy_fixture, args.new_converted_fixture, args.report), indent=2))
