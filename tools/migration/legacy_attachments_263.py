"""Copy an OFFLINE legacy world and import Skycraft attachments and synced config overrides.

Usage: py -3 tools/migration/legacy_attachments_263.py INPUT_WORLD NEW_OUTPUT_WORLD
Never edits the source or overwrites an existing destination. Only the copied random-sequence
file completes vanilla schema3565; other DataVersions stay intact;
Minecraft's own upgrader still handles vanilla data, while ItemData handles legacy custom stacks.
"""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import argparse, contextlib, gzip, json, os, shutil, struct, uuid, zlib

LIMIT = 256 * 1024 * 1024
ATTACHMENTS = b"neoforge:attachments"
OWNED = {b"skycraft:player_data": (10, b"data"), b"skycraft:overflow": (10, b"data"), b"skycraft:placed_blocks": (12, b"positions")}
# These exact explicit filenames remain registered as SYNCED in 26.3. FML 12.0.12
# reads world/syncedconfig, not the former world/serverconfig override directory.
SYNCED_CONFIGS = frozenset({"skycraft-atmosphere-server.toml", "skycraft-ocean-server.toml", "skycraft-perf-server.toml"})

@dataclass
class Node:
    kind: int
    value: object

def compound(**entries):
    return Node(10, [(key.encode(), value) for key, value in entries.items()])

def child(node, key):
    if node.kind != 10: return None
    return next((value for name, value in reversed(node.value) if name == key), None)

def put(node, key, value):
    for index in range(len(node.value) - 1, -1, -1):
        if node.value[index][0] == key:
            node.value[index] = (key, value); return
    node.value.append((key, value))

def read_nbt(data):
    if len(data) > LIMIT: raise ValueError("NBT quota exceeded")
    position = 0
    def take(count):
        nonlocal position
        if count < 0 or count > len(data) - position: raise ValueError("Truncated or oversized NBT")
        result = data[position:position+count]; position += count; return result
    def string():
        size = struct.unpack(">H", take(2))[0]; return take(size)
    def read(kind, depth=0):
        if depth > 512: raise ValueError("NBT nesting quota exceeded")
        if kind in (1, 2, 3, 4, 5, 6): return Node(kind, take({1:1,2:2,3:4,4:8,5:4,6:8}[kind]))
        if kind == 8: return Node(kind, string())
        if kind in (7, 11, 12):
            raw = take(4); size = struct.unpack(">i", raw)[0]
            if size < 0: raise ValueError("Negative NBT array size")
            return Node(kind, raw + take(size * {7:1,11:4,12:8}[kind]))
        if kind == 9:
            element = take(1)[0]; size = struct.unpack(">i", take(4))[0]
            if size < 0 or size > len(data) or (element == 0 and size): raise ValueError("Invalid NBT list")
            return Node(kind, (element, [read(element, depth+1) for _ in range(size)]))
        if kind == 10:
            values = []
            while True:
                element = take(1)[0]
                if element == 0: return Node(kind, values)
                name = string(); values.append((name, read(element, depth+1)))
        raise ValueError("Unknown NBT type " + str(kind))
    kind = take(1)[0]
    if kind != 10: raise ValueError("World NBT root must be a compound")
    name = string(); root = read(kind)
    if position != len(data): raise ValueError("Trailing bytes after NBT root")
    return name, root

def write_nbt(name, node):
    def string(value):
        if len(value) > 65535: raise ValueError("NBT string too long")
        return struct.pack(">H", len(value)) + value
    def write(node):
        if node.kind == 8: return string(node.value)
        if node.kind == 9:
            kind, values = node.value
            return bytes([kind]) + struct.pack(">i", len(values)) + b"".join(write(value) for value in values)
        if node.kind == 10:
            return b"".join(bytes([value.kind]) + string(key) + write(value) for key, value in node.value) + b"\0"
        return node.value
    return bytes([node.kind]) + string(name) + write(node)

def convert(node, counts):
    if node.kind == 9:
        for value in node.value[1]: convert(value, counts)
    if node.kind != 10: return
    caps = child(node, b"ForgeCaps")
    if caps is not None:
        if caps.kind != 10: raise ValueError("ForgeCaps must be a compound")
        native = child(node, ATTACHMENTS)
        if native is not None and native.kind != 10: raise ValueError("Native attachments must be a compound")
        for key, (kind, wrapper) in OWNED.items():
            legacy = child(caps, key)
            if legacy is None or (native is not None and child(native, key) is not None): continue
            if legacy.kind != kind: raise ValueError("Unexpected legacy attachment type for " + key.decode())
            if native is None: native = Node(10, []); put(node, ATTACHMENTS, native)
            put(native, key, Node(10, [(wrapper, legacy)]))
            counts[key.decode()] = counts.get(key.decode(), 0) + 1
    # The new attachment points at immutable parsed nodes also retained under ForgeCaps. Do not recurse
    # through the added copy; existing children are enough, and opaque legacy payloads stay byte-exact.
    for key, value in list(node.value):
        if key not in (b"ForgeCaps", ATTACHMENTS): convert(value, counts)

def unpack(data, compression):
    if compression == 3:
        if len(data) > LIMIT: raise ValueError("NBT quota exceeded")
        return data
    if compression not in (1, 2): raise ValueError("Unsupported legacy region compression " + str(compression))
    decoder = zlib.decompressobj(31 if compression == 1 else 15)
    result = decoder.decompress(data, LIMIT + 1)
    if len(result) > LIMIT or not decoder.eof or decoder.unused_data: raise ValueError("Compressed NBT quota/truncation/trailing-data failure")
    return result

def migrate_bytes(data, compression, counts, *, random_sequences=False):
    raw = unpack(data, compression); name, root = read_nbt(raw)
    before = sum(counts.values()); convert(root, counts); convert_survival_rule(root, counts)
    if random_sequences: convert_random_sequences(root, counts)
    if before == sum(counts.values()): return None
    rebuilt = write_nbt(name, root)
    # The lossless typed writer must preserve every field, number type and raw modified-UTF8 string.
    if read_nbt(rebuilt) != (name, root): raise ValueError("NBT writer round trip mismatch")
    return gzip.compress(rebuilt, mtime=0) if compression == 1 else zlib.compress(rebuilt) if compression == 2 else rebuilt

def convert_random_sequences(root, counts):
    """Complete vanilla's 3565 schema transition without resetting any PRNG state.

    Actual 1.20.1 RandomSequences creates new streams from world seed + sequence ID,
    equivalent to salt=0/include_world_seed=true/include_sequence_id=true. Vanilla's
    RandomSequenceSettingsFix wraps the flat map but never supplies salt; 26.3 now
    requires salt. This ONE file advances to that precise schema boundary so native
    DFU cannot wrap it a second time. Other files retain their original DataVersion.
    """
    version = child(root, b"DataVersion")
    if version is None or version.kind != 3:
        raise ValueError("Random sequences require an explicit integer DataVersion")
    old_version = struct.unpack(">i", version.value)[0]
    if old_version >= 3565:
        return
    data = child(root, b"data")
    if data is None or data.kind != 10:
        raise ValueError("Legacy random sequences require a compound data map")
    for name, sequence in data.value:
        source = child(sequence, b"source")
        if sequence.kind != 10 or source is None or source.kind != 12 or struct.unpack_from(">i", source.value)[0] != 2:
            raise ValueError("Unrecognized legacy random-sequence state: " + repr(name))
    put(root, b"data", compound(salt=Node(3, struct.pack(">i", 0)), include_world_seed=Node(1, b"\x01"),
                               include_sequence_id=Node(1, b"\x01"), sequences=data))
    put(root, b"DataVersion", Node(3, struct.pack(">i", 3565)))
    key = "vanilla_schema:random_sequences_3565"
    counts[key] = counts.get(key, 0) + 1

def convert_survival_rule(root, counts):
    data = child(root, b"Data")
    if data is None or data.kind != 10: return
    rules = child(data, b"GameRules")
    if rules is None or rules.kind != 10: return
    legacy = child(rules, b"skycraftSurvivalMode")
    if legacy is None: return
    if legacy.kind != 8 or legacy.value.lower() not in (b"true", b"false"):
        raise ValueError("Invalid legacy skycraftSurvivalMode boolean")
    # Native GameRuleRegistryFix preserves already namespaced typed values, then the
    # native LevelDatToSavedDataFileFix extracts this map to data/minecraft/game_rules.dat.
    # Remove the obsolete unregistered key only in the copied world; the source retains it.
    if child(rules, b"skycraft:survival_mode") is None:
        put(rules, b"skycraft:survival_mode", Node(1, b"\x01" if legacy.value.lower() == b"true" else b"\x00"))
    rules.value = [(key, value) for key, value in rules.value if key != b"skycraftSurvivalMode"]
    key = "game_rule:skycraft:survival_mode"
    counts[key] = counts.get(key, 0) + 1

def region(path, counts):
    raw = bytearray(path.read_bytes())
    # Vanilla RegionFile accepts EOF on a newly created zero-length region (often POI).
    # It has no allocated chunks or attachments; preserve the empty file byte-for-byte.
    if not raw: return False
    if len(raw) < 8192: raise ValueError("Truncated region " + str(path))
    if len(raw) % 4096: raise ValueError("Unaligned region " + str(path))
    rx, rz = map(int, path.stem.split(".")[1:]); changed = False
    for index in range(1024):
        location = struct.unpack_from(">I", raw, index*4)[0]
        if not location: continue
        offset = (location >> 8)*4096; sectors = location & 255
        if offset < 8192 or not sectors or offset + sectors*4096 > len(raw): raise ValueError("Invalid region location")
        length = struct.unpack_from(">I", raw, offset)[0]
        if length < 1 or length + 4 > sectors*4096: raise ValueError("Invalid region chunk length")
        compression = raw[offset+4]; external = bool(compression & 128); compression &= 127
        cx, cz = rx*32 + index%32, rz*32 + index//32
        external_path = path.parent / ("c.%d.%d.mcc" % (cx, cz))
        data = external_path.read_bytes() if external else bytes(raw[offset+5:offset+4+length])
        converted = migrate_bytes(data, compression, counts)
        if converted is None: continue
        changed = True
        payload = struct.pack(">I", len(converted)+1) + bytes([compression]) + converted
        needed = (len(payload)+4095)//4096
        if needed > 255:
            external_path.write_bytes(converted)
            payload = struct.pack(">I", 1) + bytes([compression | 128]); needed = 1
        new_offset = len(raw)//4096
        if new_offset >= 1 << 24: raise ValueError("Region offset exceeds Anvil limit")
        raw.extend(payload + bytes(needed*4096-len(payload)))
        struct.pack_into(">I", raw, index*4, (new_offset << 8) | needed)
    if changed: path.write_bytes(raw)
    return changed

@contextlib.contextmanager
def offline_lock(world):
    path = world / "session.lock"
    if not path.exists(): yield; return
    with path.open("r+b") as stream:
        try:
            if os.name == "nt":
                import msvcrt; msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl; fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error: raise RuntimeError("Stop Minecraft/server before copying this world") from error
        try: yield
        finally:
            if os.name == "nt":
                stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else: fcntl.flock(stream.fileno(), fcntl.LOCK_UN)

def migrate_configs(world):
    copied, existing = [], []
    for name in sorted(SYNCED_CONFIGS):
        legacy = world / "serverconfig" / name
        if not legacy.is_file(): continue
        target = world / "syncedconfig" / name
        if target.exists():
            existing.append(str(target.relative_to(world))); continue
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(legacy, target)
        copied.append(str(target.relative_to(world)))
    return {"copied": copied, "existing_native_overrides_preserved": existing}

def migrate(source, destination):
    source = source.resolve(strict=True); destination = destination.resolve()
    if not source.is_dir() or not (source / "level.dat").exists(): raise ValueError("Input is not a Minecraft world")
    if destination.exists() or destination == source or source in destination.parents or destination in source.parents:
        raise ValueError("Output must be a new separate directory outside the source world")
    counts = {}; files = []
    staging = destination.with_name(destination.name + ".migrating-" + uuid.uuid4().hex)
    try:
        with offline_lock(source):
            shutil.copytree(source, staging)
            for path in sorted(staging.rglob("*.dat")):
                data = path.read_bytes(); compression = 1 if data.startswith(b"\x1f\x8b") else 3
                changed = migrate_bytes(data, compression, counts,
                                        random_sequences=path.name == "random_sequences.dat" and path.parent.name in {"data", "minecraft"})
                if changed is not None:
                    path.write_bytes(changed); files.append(str(path.relative_to(staging)))
            for path in sorted(staging.rglob("r.*.*.mca")):
                if region(path, counts): files.append(str(path.relative_to(staging)))
            configs = migrate_configs(staging)
        manifest = {"status": "complete", "source": str(source), "target": "Minecraft 26.3 / NeoForge",
                    "attachments": {key:value for key,value in counts.items() if key in {owned.decode() for owned in OWNED}},
                    "game_rules": {key.removeprefix("game_rule:"):value for key,value in counts.items() if key.startswith("game_rule:")},
                    "vanilla_schema_repairs": {key.removeprefix("vanilla_schema:"):value for key,value in counts.items() if key.startswith("vanilla_schema:")},
                    "changed_files": files, "synced_configs": configs,
                    "vanilla_data_version": "unchanged except copied random_sequences.dat advances to exact schema3565; native upgrade still required" if counts.get("vanilla_schema:random_sequences_3565") else "unchanged; native Minecraft upgrade still required", "source_modified": False}
        (staging / "skycraft-26.3-migration.json").write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8")
        staging.rename(destination)  # completed copies only; never publish an incomplete world under the requested name
        return manifest
    except Exception as error:
        if staging.is_dir():
            (staging / "skycraft-26.3-migration-failed.txt").write_text(str(error)+"\nDo not open this incomplete copy. Original world was not modified.\n", encoding="utf-8")
        raise

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("source", type=Path); parser.add_argument("destination", type=Path)
    args = parser.parse_args(); print(json.dumps(migrate(args.source, args.destination), indent=2))
