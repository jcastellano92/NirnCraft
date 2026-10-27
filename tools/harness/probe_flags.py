"""Probe-owned launcher flags: journal before writes, restore only after verified close."""
import json
import base64
import re
from pathlib import Path

START = b'-Dnirncraft.harness=1'
END = b'-Dnirncraft.harness.end=1'
BLOCK = re.compile(re.escape(START) + rb'.*?' + re.escape(END))


def block(data):
    lines = [line for line in data.splitlines(keepends=True) if line.startswith(b'JvmArgs=')]
    if len(lines) != 1:
        raise ValueError('expected exactly one JvmArgs line')
    matches = list(BLOCK.finditer(lines[0]))
    if len(matches) > 1 or ((START in lines[0] or END in lines[0]) and len(matches) != 1):
        raise ValueError('ambiguous harness marker')
    return matches[0].group() if matches else None


def properties(data):
    found = {}
    for line in data.splitlines(keepends=True):
        stripped = line.strip()
        if not stripped or stripped.startswith((b'#', b'!')): continue
        if b'=' not in stripped: raise ValueError('ambiguous properties line')
        key = stripped.split(b'=', 1)[0].strip()
        if key in found: raise ValueError('duplicate property')
        found[key] = line
    return found


def snapshot(cfg, flags, evidence):
    cfg, flags, evidence = map(Path, (cfg, flags, evidence))
    original_cfg = cfg.read_bytes()
    original_flags = flags.read_bytes() if flags.exists() else None
    block(original_cfg)
    if original_flags is not None: properties(original_flags)
    (evidence / 'flags-original-cfg.bin').write_bytes(original_cfg)
    if original_flags is not None: (evidence / 'flags-original-properties.bin').write_bytes(original_flags)
    (evidence / 'flags-original.json').write_text(json.dumps({'properties_present': original_flags is not None}), encoding='utf-8')


def format_marker(jvm, raw_args=()):
    raw_args = tuple(raw_args)
    if any(arg != '-XX:NativeMemoryTracking=summary' for arg in raw_args) or len(raw_args) > 1:
        raise ValueError('unsupported raw probe JVM option')
    extra = ' '.join([*(f'-D{k}={v}' for k, v in sorted(jvm.items())), *raw_args])
    return (f'{START.decode()} {extra} {END.decode()}').encode() if extra else None


def journal(jvm, evidence, atomic, raw_args=()):
    marker = format_marker(jvm, raw_args)
    values = dict(jvm); values['nirncraft.harness.authoritative'] = 'true'
    encoded = ''.join(f"{k}={str(v).replace(chr(92), '/')}\n" for k, v in sorted(values.items())).encode()
    record = {'marker': marker.decode() if marker else None, 'properties': encoded.decode()}
    atomic(Path(evidence) / 'flags-expected.json', json.dumps(record).encode())


def guard_before_write(cfg, flags, evidence):
    cfg, flags, evidence = map(Path, (cfg, flags, evidence))
    original = (evidence / 'flags-original-cfg.bin').read_bytes()
    present = json.loads((evidence / 'flags-original.json').read_text())['properties_present']
    old_flags = (evidence / 'flags-original-properties.bin').read_bytes() if present else None
    current = flags.read_bytes() if flags.exists() else None
    expected_path = evidence / 'flags-expected.json'
    if expected_path.exists():
        expected = json.loads(expected_path.read_text(encoding='utf-8'))
        expected_marker = expected['marker'].encode() if expected['marker'] is not None else None
        if block(cfg.read_bytes()) == expected_marker and current == expected['properties'].encode(): return
    if block(cfg.read_bytes()) != block(original) or current != old_flags:
        raise RuntimeError('harness flags changed before probe write')


def restore(cfg, flags, evidence, closed, atomic):
    cfg, flags, evidence = map(Path, (cfg, flags, evidence))
    if not closed: raise RuntimeError('client closure or launch handoff unverified')
    original_cfg = (evidence / 'flags-original-cfg.bin').read_bytes()
    present = json.loads((evidence / 'flags-original.json').read_text())['properties_present']
    original_flags = (evidence / 'flags-original-properties.bin').read_bytes() if present else None
    journal_path = evidence / 'flags-expected.json'
    current_cfg = cfg.read_bytes()
    current_flags = flags.read_bytes() if flags.exists() else None
    if not journal_path.exists():
        if block(current_cfg) != block(original_cfg) or current_flags != original_flags:
            raise RuntimeError('flags changed without probe write journal')
        return
    expected = json.loads(journal_path.read_text(encoding='utf-8'))
    expected_block = expected['marker'].encode() if expected['marker'] is not None else None
    previous_block = block(original_cfg)
    current_block = block(current_cfg)
    restore_path = evidence / 'flags-restore-journal.json'
    if restore_path.exists() and current_block == previous_block and current_block != expected_block:
        state = json.loads(restore_path.read_text(encoding='utf-8'))
        def decode(value): return None if value is None else base64.b64decode(value)
        source_flags, target_flags = decode(state['source_flags']), decode(state['target_flags'])
        if current_flags == target_flags: return
        if current_flags != source_flags: raise RuntimeError('properties changed during interrupted restore')
        if target_flags is None: flags.unlink(missing_ok=True)
        else: atomic(flags, target_flags)
        return
    if current_block not in (expected_block, previous_block):
        raise RuntimeError('harness marker changed during probe')
    old = properties(original_flags or b'')
    want = properties(expected['properties'].encode())
    now = properties(current_flags or b'')
    # The journal precedes both writes: each file may still be in its pre-write state.
    original_unchanged = current_flags == original_flags
    if current_block == previous_block and current_block != expected_block and not original_unchanged:
        raise RuntimeError('harness marker reverted while probe properties remain')
    if not original_unchanged:
        for key, value in want.items():
            if now.get(key) != value: raise RuntimeError('probe-owned property changed during probe')
        lines = (current_flags or b'').splitlines(keepends=True)
        restored = []
        for line in lines:
            key = line.strip().split(b'=', 1)[0].strip() if b'=' in line and not line.lstrip().startswith((b'#', b'!')) else None
            if key in want:
                if key in old: restored.append(old[key])
            else: restored.append(line)
        for key, line in old.items():
            if key not in want and key not in now: restored.append(line)
        for line in (original_flags or b'').splitlines(keepends=True):
            if (not line.strip() or line.lstrip().startswith((b'#', b'!'))) and line not in restored:
                restored.append(line)
        new_flags = b''.join(restored)
    else: new_flags = current_flags
    new_cfg = current_cfg
    if current_block == expected_block and current_block != previous_block:
        replacement = previous_block or b''
        lines = current_cfg.splitlines(keepends=True)
        index = next(i for i, line in enumerate(lines) if line.startswith(b'JvmArgs='))
        lines[index] = lines[index].replace(current_block, replacement, 1)
        new_cfg = b''.join(lines)
    # Validate both before either write; never undo a concurrent marker/property edit.
    target = new_flags if (new_flags or present) else None
    def encode(value): return None if value is None else base64.b64encode(value).decode()
    atomic(restore_path, json.dumps({'source_flags': encode(current_flags), 'target_flags': encode(target)}).encode())
    if new_cfg != current_cfg: atomic(cfg, new_cfg)
    if new_flags != current_flags:
        if new_flags or present: atomic(flags, new_flags)
        else: flags.unlink(missing_ok=True)
