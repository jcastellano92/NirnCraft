"""Read-only offline attachment inventory and selected native load/save/reload comparisons.

scan WORLD OUTPUT [--selection BASELINE_JSON] reads real Anvil/NBT records, never edits a world.
compare BEFORE_JSON AFTER_JSON OUTPUT compares selected item counts/custom markers and placed-position sets.
Empty attachments are explicitly reported as empty; attachment counts never imply nonempty payloads.
"""
from __future__ import annotations
import argparse, collections, gzip, hashlib, json, struct
from pathlib import Path
import legacy_attachments_263 as nbt


def number(node, default=0):
    if node is None:
        return default
    return struct.unpack({1: ">b", 2: ">h", 3: ">i", 4: ">q"}[node.kind], node.value)[0]


def string(node, default=""):
    return node.value.decode("utf-8") if node is not None and node.kind == 8 else default


def long_values(node):
    if node is None:
        return []
    if node.kind != 12:
        raise ValueError("Expected a real NBT long array for placed positions")
    count = struct.unpack_from(">i", node.value)[0]
    return [struct.unpack_from(">q", node.value, 4 + index * 8)[0] for index in range(count)]


def region_chunks(path, selected=None):
    raw = path.read_bytes()
    if not raw:
        return
    if len(raw) < 8192 or len(raw) % 4096:
        raise ValueError("Invalid Anvil region: " + str(path))
    rx, rz = map(int, path.stem.split(".")[1:])
    for index in range(1024):
        cx, cz = rx * 32 + index % 32, rz * 32 + index // 32
        if selected is not None and (cx, cz) not in selected:
            continue
        location = struct.unpack_from(">I", raw, index * 4)[0]
        if not location:
            continue
        offset, sectors = (location >> 8) * 4096, location & 255
        if offset < 8192 or not sectors or offset + sectors * 4096 > len(raw):
            raise ValueError("Invalid Anvil chunk allocation")
        size = struct.unpack_from(">I", raw, offset)[0]
        if size < 1 or size + 4 > sectors * 4096:
            raise ValueError("Invalid Anvil chunk length")
        compression = raw[offset + 4]
        payload = (path.parent / f"c.{cx}.{cz}.mcc").read_bytes() if compression & 128 else raw[offset + 5:offset + 4 + size]
        yield cx, cz, nbt.read_nbt(nbt.unpack(payload, compression & 127))[1]


def attachment(owner, key):
    native = nbt.child(owner, nbt.ATTACHMENTS)
    return nbt.child(native, key) if native is not None else None


def marker(stack):
    legacy = nbt.child(stack, b"tag")
    components = nbt.child(stack, b"components")
    custom = nbt.child(components, b"minecraft:custom_data") if components else None
    custom = custom if custom is not None else legacy
    if custom is None:
        return {}
    result = {}
    for key in (b"migration_probe", b"probe_kind", b"probe_value"):
        value = nbt.child(custom, key)
        if value is not None:
            result[key.decode()] = string(value) if value.kind == 8 else number(value)
    return result


def item_state(stack):
    old = nbt.child(stack, b"tag")
    components = nbt.child(stack, b"components")
    damage = nbt.child(components, b"minecraft:damage") if components else None
    if damage is None and old is not None:
        damage = nbt.child(old, b"Damage")
    enchantments = nbt.child(components, b"minecraft:enchantments") if components else None
    levels = {}
    if enchantments is not None:
        levels = {key.decode(): number(value) for key, value in enchantments.value}
    elif old is not None:
        entries = nbt.child(old, b"Enchantments")
        for entry in entries.value[1] if entries is not None else []:
            levels[string(nbt.child(entry, b"id"))] = number(nbt.child(entry, b"lvl"))
    return {"damage": number(damage), "enchantments": dict(sorted(levels.items()))}


def overflow_summary(be):
    wrapper = attachment(be, b"skycraft:overflow")
    data = nbt.child(wrapper, b"data") if wrapper is not None else None
    rows = nbt.child(data, b"e") if data is not None else None
    totals = collections.Counter()
    identities = []
    native_rows = 0
    for row in rows.value[1] if rows is not None else []:
        stack = nbt.child(row, b"s")
        count = number(nbt.child(row, b"n"))
        identity = string(nbt.child(stack, b"id"))
        totals[identity] += count
        native_rows += nbt.child(stack, b"components") is not None or nbt.child(stack, b"count") is not None
        identities.append({"id": identity, "count": count, "marker": marker(stack), "item_state": item_state(stack)})
    return {"present": wrapper is not None, "rows": len(identities), "native_rows": native_rows, "items": sum(totals.values()),
            "items_by_id": dict(sorted(totals.items())), "ordered_rows": identities}


def dimension(region):
    if region == "region":
        return "minecraft:overworld"
    if region == "DIM-1/region":
        return "minecraft:the_nether"
    if region == "DIM1/region":
        return "minecraft:the_end"
    parts = region.split("/")
    return parts[1] + ":" + "/".join(parts[2:-1]) if parts[0] == "dimensions" else region


def block_state_at(chunk, position):
    x, y, z = position
    sections = nbt.child(chunk, b"sections")
    for section in sections.value[1] if sections is not None else []:
        if number(nbt.child(section, b"Y")) != y // 16:
            continue
        states = nbt.child(section, b"block_states")
        palette = nbt.child(states, b"palette")
        if palette is None or not palette.value[1]:
            raise ValueError("Missing block palette for selected position")
        entries = palette.value[1]
        index = 0
        if len(entries) > 1:
            bits = max(4, (len(entries) - 1).bit_length())
            per_long = 64 // bits
            cell = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15)
            packed = long_values(nbt.child(states, b"data"))
            index = (packed[cell // per_long] >> ((cell % per_long) * bits)) & ((1 << bits) - 1)
        entry = entries[index]
        identity = string(nbt.child(entry, b"Name")) or string(nbt.child(entry, b"id")) or string(nbt.child(entry, b""))
        properties = nbt.child(entry, b"Properties") or nbt.child(entry, b"properties")
        values = {key.decode(): string(value) for key, value in properties.value} if properties is not None else {}
        if identity.startswith("minecraft:") and identity.endswith("_bed"):
            # Native AbstractBedBlock's defaults; compact 26.3 palettes omit them.
            values = {"facing": "north", "part": "foot", "occupied": "false", **values}
        return {"id": identity, "properties": dict(sorted(values.items()))}
    raise ValueError("Missing chunk section for selected position")


def scan(world, selection=None):
    world = world.resolve(strict=True)
    manifest = world / "skycraft-26.3-migration.json"
    selected = collections.defaultdict(set)
    selected_positions = collections.defaultdict(set)
    if selection:
        for probe in selection["probes"]:
            selected[probe["region"]].add(tuple(probe["chunk"]))
            selected_positions[(probe["region"], tuple(probe["chunk"]))].update(tuple(be["position"]) for be in probe.get("block_entities", []))
        files = [(relative, world / relative) for relative in sorted(selected)]
    elif manifest.is_file():
        data = json.loads(manifest.read_text())
        files = [(value.replace("\\", "/"), world / value.replace("\\", "/")) for value in data["changed_files"] if value.endswith(".mca")]
    else:
        files = [(path.relative_to(world).as_posix(), path) for path in sorted(world.rglob("*.mca")) if path.parent.name == "region"]
    records, statistics, inputs = [], collections.Counter(), []
    with nbt.offline_lock(world):
        for relative, path in files:
            if not path.exists():
                # Exact 26.3 DimensionStorageFileFix relocates vanilla dimensions; keep
                # logical legacy coordinates stable across the actual native rewrite.
                for old, native in (("region/", "dimensions/minecraft/overworld/region/"),
                                    ("DIM-1/region/", "dimensions/minecraft/the_nether/region/"),
                                    ("DIM1/region/", "dimensions/minecraft/the_end/region/")):
                    if relative.startswith(old):
                        path = world / (native + relative[len(old):])
                        break
            inputs.append({"region": relative, "physical_region": path.relative_to(world).as_posix(),
                           "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
            for cx, cz, chunk in region_chunks(path, selected[relative] if selection else None):
                statistics["chunks_read"] += 1
                placed = attachment(chunk, b"skycraft:placed_blocks")
                positions = sorted(set(long_values(nbt.child(placed, b"positions") if placed else None)))
                if placed:
                    statistics["placed_attachments"] += 1
                    statistics["nonempty_placed_attachments"] += bool(positions)
                    statistics["placed_positions"] += len(positions)
                block_entities = nbt.child(chunk, b"block_entities")
                entities = []
                for be in block_entities.value[1] if block_entities is not None else []:
                    overflow = overflow_summary(be)
                    if not overflow["present"] and not selection:
                        continue
                    xyz = [number(nbt.child(be, axis)) for axis in (b"x", b"y", b"z")]
                    entities.append({"position": xyz, "block_entity": string(nbt.child(be, b"id")), "overflow": overflow,
                                     "additional_fields": sorted(key.decode() for key, _ in be.value if key not in {b"id", b"x", b"y", b"z", b"keepPacked"})})
                    statistics["overflow_attachments"] += overflow["present"]
                    statistics["nonempty_overflow_attachments"] += bool(overflow["items"])
                    statistics["overflow_items"] += overflow["items"]
                if placed or entities or selection:
                    positions_to_sample = selected_positions[(relative, (cx, cz))] | {tuple(be["position"]) for be in entities}
                    records.append({"region": relative, "dimension": dimension(str(Path(relative).parent).replace("\\", "/")),
                                    "chunk": [cx, cz], "data_version": number(nbt.child(chunk, b"DataVersion")),
                                    "placed_present": placed is not None, "placed_positions": positions, "block_entities": entities,
                                    "sampled_blocks": [{"position": list(pos), "state": block_state_at(chunk, pos)} for pos in sorted(positions_to_sample)]})
    if selection:
        wanted = {(p["region"], tuple(p["chunk"])) for p in selection["probes"]}
        probes = [record for record in records if (record["region"], tuple(record["chunk"])) in wanted]
    else:
        def score(record):
            populated = bool(record["placed_positions"]) or any(be["overflow"]["items"] for be in record["block_entities"])
            return not populated, not bool(record["block_entities"]), abs(record["chunk"][0]) + abs(record["chunk"][1])
        probes = sorted(records, key=score)[:3]
    result = {"schema": 1, "world": str(world), "mode": "selected_chunks" if selection else "converted_regions" if manifest.exists() else "all_regions",
            "read_only": True, "statistics": dict(statistics), "region_inputs": inputs, "probes": probes,
            "limits": ["Empty attachments do not prove nonempty payload conservation.",
                       "Native item codecs may change serialized layout; compare full counts and explicit fixture identity markers.",
                       "Run scans only while the target server is stopped."]}
    provenance = world / "skycraft-populated-legacy-fixture.json"
    if provenance.is_file():
        result["fixture_provenance"] = json.loads(provenance.read_text())
    return result


def compare(before, after):
    failures, transitions, checks = [], [], 0
    actual = {(p["region"], tuple(p["chunk"])): p for p in after["probes"]}
    for original in before["probes"]:
        key = original["region"], tuple(original["chunk"])
        current = actual.get(key)
        checks += 1
        if current is None:
            failures.append(f"Missing probed chunk {key}")
            continue
        checks += 1
        if original["placed_positions"] != current["placed_positions"]:
            failures.append(f"Placed-position set changed in {key}")
        checks += 1
        if current["data_version"] <= original["data_version"]:
            failures.append(f"No actual native chunk rewrite is evidenced by DataVersion in {key}")
        entities = {tuple(be["position"]): be for be in current["block_entities"]}
        old_blocks = {tuple(block["position"]): block["state"] for block in original.get("sampled_blocks", [])}
        new_blocks = {tuple(block["position"]): block["state"] for block in current.get("sampled_blocks", [])}
        for be in original["block_entities"]:
            position = tuple(be["position"])
            result = entities.get(position)
            checks += 1
            if result is None or result["block_entity"] != be["block_entity"]:
                # Actual DataFixers schema4885 removes minecraft:bed block entities.
                # Accept only its empty, attachment-free records with the exact bed
                # block and normalized properties preserved at the original location.
                old_state = old_blocks.get(position)
                if (result is None and be["block_entity"] == "minecraft:bed"
                        and original["data_version"] < 4885 <= current["data_version"]
                        and not be["overflow"]["present"] and not be["overflow"]["items"]
                        and not be.get("additional_fields", ["uninspected legacy payload"])
                        and old_state is not None and old_state["id"].startswith("minecraft:")
                        and old_state["id"].endswith("_bed") and new_blocks.get(position) == old_state):
                    transitions.append({"position": list(position), "fix": "native4885 RemoveBlockEntityTagFix(minecraft:bed)",
                                        "block_state_preserved": old_state, "removed_attachment_payload": False})
                    continue
                failures.append(f"Missing/changed actual block entity {position} in {key}")
                continue
            for field in ("items_by_id", "ordered_rows"):
                checks += 1
                if result["overflow"][field] != be["overflow"][field]:
                    failures.append(f"Overflow {field} changed at {position}")
            if be["overflow"]["items"]:
                checks += 1
                if result["overflow"]["native_rows"] != result["overflow"]["rows"]:
                    failures.append(f"Nonempty overflow was not serialized through native item components at {position}")
    return {"schema": 1, "checks": checks, "failures": failures, "passed": not failures, "native_schema_transitions": transitions,
            "before_world": before["world"], "after_world": after["world"],
            "nonempty_before": before["statistics"].get("nonempty_overflow_attachments", 0) + before["statistics"].get("nonempty_placed_attachments", 0)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    read = sub.add_parser("scan")
    read.add_argument("world", type=Path)
    read.add_argument("output", type=Path)
    read.add_argument("--selection", type=Path)
    check = sub.add_parser("compare")
    check.add_argument("before", type=Path)
    check.add_argument("after", type=Path)
    check.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.command == "scan":
        result = scan(args.world, json.loads(args.selection.read_text()) if args.selection else None)
    else:
        result = compare(json.loads(args.before.read_text()), json.loads(args.after.read_text()))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result.get("statistics", {"passed": result["passed"], "checks": result["checks"]}) if args.command == "compare" else result["statistics"]))
    return 1 if args.command == "compare" and not result["passed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
