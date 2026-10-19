#!/usr/bin/env python3
"""
JDWP HotSwap client for the NirnCraft persistent dev server (Tier 1).

Compares compiled .class files in mod/build/classes/java/main against the server's
boot snapshot (classes-boot) and last hotswapped state (classes-last):
  1. Checks class schemas (class flags, superclass, interfaces, fields, methods).
     On any structural change (field/method/signature/modifier/new class), exits 4
     with a clear reason so devserver.ps1 falls back to a server restart.
  2. Connects to the running server JVM's JDWP agent on 127.0.0.1:<port> and invokes
     VirtualMachine.RedefineClasses (CommandSet 1, Command 18) for all modified
     classes currently loaded in the JVM.
  3. Updates classes-last on success and exits 0.
"""
import argparse
import hashlib
import os
import shutil
import socket
import struct
import sys
import time
from pathlib import Path

JDWP_HANDSHAKE = b"JDWP-Handshake"
FLAG_REPLY = 0x80

JDWP_ERRORS = {
    0: "NONE",
    20: "INVALID_OBJECT",
    21: "INVALID_CLASS",
    22: "CLASS_NOT_PREPARED",
    60: "INVALID_CLASS_FORMAT",
    61: "CIRCULAR_CLASS_DEFINITION",
    62: "FAILS_VERIFICATION",
    63: "ADD_METHOD_NOT_IMPLEMENTED (method added)",
    64: "SCHEMA_CHANGE_NOT_IMPLEMENTED (field added/removed/changed)",
    65: "INVALID_TYPESTATE",
    66: "HIERARCHY_CHANGE_NOT_IMPLEMENTED (superclass/interface changed)",
    67: "DELETE_METHOD_NOT_IMPLEMENTED (method removed or signature changed)",
    68: "UNSUPPORTED_VERSION",
    69: "NAMES_DONT_MATCH",
    70: "CLASS_MODIFIERS_CHANGE_NOT_IMPLEMENTED (class modifiers changed)",
    71: "METHOD_MODIFIERS_CHANGE_NOT_IMPLEMENTED (method modifiers changed)",
    99: "NOT_IMPLEMENTED",
    110: "OUT_OF_MEMORY",
    111: "ACCESS_DENIED",
    112: "VM_DEAD",
    113: "INTERNAL",
}


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_class_schema(data: bytes):
    """Extracts (internal_name, access_flags, super_name, interfaces, fields_dict, methods_dict) from a .class file."""
    magic, _minor, _major, cp_count = struct.unpack_from(">IHHH", data, 0)
    if magic != 0xCAFEBABE:
        raise ValueError("Not a valid .class file (bad magic)")
    pos = 10
    cp = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # Utf8
            length = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            cp[i] = data[pos:pos + length].decode("utf-8", errors="replace")
            pos += length
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            cp[i] = struct.unpack_from(">H", data, pos)[0]
            pos += 2
        elif tag == 15:  # MethodHandle
            pos += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):  # Int, Float, Refs, NameAndType, Dynamic, InvokeDynamic
            pos += 4
        elif tag in (5, 6):  # Long, Double (take 2 slots)
            pos += 8
            i += 1
        else:
            raise ValueError(f"Unknown constant pool tag {tag} at slot {i}")
        i += 1

    def class_name(idx: int) -> str:
        if idx == 0:
            return ""
        name_idx = cp[idx]
        return cp[name_idx] if isinstance(name_idx, int) else str(name_idx)

    access_flags, this_idx, super_idx, if_count = struct.unpack_from(">HHHH", data, pos)
    pos += 8
    interfaces = []
    for _ in range(if_count):
        if_idx = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        interfaces.append(class_name(if_idx))

    fields = {}
    fields_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(fields_count):
        f_flags, f_name_idx, f_desc_idx, attr_count = struct.unpack_from(">HHHH", data, pos)
        pos += 8
        for _ in range(attr_count):
            _, attr_len = struct.unpack_from(">HI", data, pos)
            pos += 6 + attr_len
        fields[(cp[f_name_idx], cp[f_desc_idx])] = f_flags

    methods = {}
    methods_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(methods_count):
        m_flags, m_name_idx, m_desc_idx, attr_count = struct.unpack_from(">HHHH", data, pos)
        pos += 8
        for _ in range(attr_count):
            _, attr_len = struct.unpack_from(">HI", data, pos)
            pos += 6 + attr_len
        methods[(cp[m_name_idx], cp[m_desc_idx])] = m_flags

    return {
        "name": class_name(this_idx),
        "flags": access_flags,
        "super": class_name(super_idx),
        "interfaces": tuple(interfaces),
        "fields": fields,
        "methods": methods,
    }


def diff_schema(boot_bytes: bytes, new_bytes: bytes):
    """Returns None if schemas match for standard HotSwap, or a human-readable reason string on schema change."""
    old = parse_class_schema(boot_bytes)
    cur = parse_class_schema(new_bytes)
    cls = cur["name"].replace("/", ".")
    if old["flags"] != cur["flags"]:
        return f"class modifiers changed in {cls} (0x{old['flags']:04x} -> 0x{cur['flags']:04x})"
    if old["super"] != cur["super"]:
        return f"superclass changed in {cls} ({old['super']} -> {cur['super']})"
    if old["interfaces"] != cur["interfaces"]:
        return f"interfaces changed in {cls}"

    old_f = old["fields"]
    cur_f = cur["fields"]
    for k in cur_f:
        if k not in old_f:
            return f"field added in {cls}: {k[0]} ({k[1]})"
    for k in old_f:
        if k not in cur_f:
            return f"field removed in {cls}: {k[0]} ({k[1]})"
        if old_f[k] != cur_f[k]:
            return f"field modifiers changed in {cls}: {k[0]} ({k[1]})"

    old_m = old["methods"]
    cur_m = cur["methods"]
    for k in cur_m:
        if k not in old_m:
            return f"method added in {cls}: {k[0]}{k[1]}"
    for k in old_m:
        if k not in cur_m:
            return f"method removed or signature changed in {cls}: {k[0]}{k[1]}"
        if old_m[k] != cur_m[k]:
            return f"method modifiers changed in {cls}: {k[0]}{k[1]}"
    return None


class JdwpClient:
    def __init__(self, host: str, port: int, timeout: float = 15.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.sendall(JDWP_HANDSHAKE)
        reply = self._recv_exact(len(JDWP_HANDSHAKE))
        if reply != JDWP_HANDSHAKE:
            raise RuntimeError(f"Bad JDWP handshake reply: {reply!r}")
        self.next_id = 1
        self.ref_type_id_size = 8

    def _recv_exact(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise EOFError("Connection closed by JDWP server")
            buf.extend(chunk)
        return bytes(buf)

    def command(self, cmd_set: int, cmd: int, payload: bytes = b""):
        req_id = self.next_id
        self.next_id += 1
        header = struct.pack(">IIBBB", 11 + len(payload), req_id, 0, cmd_set, cmd)
        self.sock.sendall(header + payload)
        while True:
            hdr = self._recv_exact(11)
            length, pkt_id, flags = struct.unpack_from(">IIB", hdr, 0)
            body = self._recv_exact(length - 11) if length > 11 else b""
            if (flags & FLAG_REPLY) == 0:
                # Asynchronous VM event packet (e.g. VMStart); ignore
                continue
            if pkt_id == req_id:
                err = struct.unpack_from(">H", hdr, 9)[0]
                return err, body

    def fetch_id_sizes(self):
        err, body = self.command(1, 7)  # VirtualMachine.IDSizes
        if err != 0:
            raise RuntimeError(f"IDSizes failed with JDWP error {err}")
        _, _, _, self.ref_type_id_size, _ = struct.unpack(">IIIII", body[:20])

    def classes_by_signature(self, jni_sig: str):
        raw = jni_sig.encode("utf-8")
        payload = struct.pack(">I", len(raw)) + raw
        err, body = self.command(1, 2, payload)  # VirtualMachine.ClassesBySignature
        if err != 0:
            raise RuntimeError(f"ClassesBySignature({jni_sig}) failed with JDWP error {err}")
        count = struct.unpack_from(">I", body, 0)[0]
        pos = 4
        type_ids = []
        for _ in range(count):
            pos += 1  # refTypeTag
            type_ids.append(body[pos:pos + self.ref_type_id_size])
            pos += self.ref_type_id_size
            pos += 4  # status
        return type_ids

    def redefine_classes(self, definitions):
        """definitions: list of (ref_type_id_bytes, class_bytes)."""
        parts = [struct.pack(">I", len(definitions))]
        for ref_id, cls_bytes in definitions:
            parts.append(ref_id)
            parts.append(struct.pack(">I", len(cls_bytes)))
            parts.append(cls_bytes)
        err, _ = self.command(1, 18, b"".join(parts))  # VirtualMachine.RedefineClasses
        return err

    def close(self):
        try:
            self.command(1, 6)  # VirtualMachine.Dispose
        except Exception:
            pass
        try:
            self.sock.close()
        except Exception:
            pass


def collect_classes(root: Path):
    out = {}
    if not root.exists():
        return out
    for p in root.rglob("*.class"):
        rel = p.relative_to(root).as_posix()
        out[rel] = p
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--classes", required=True, help="Compiled classes dir (mod/build/classes/java/main)")
    ap.add_argument("--boot", required=True, help="Boot snapshot dir (mod/run/devworld/classes-boot)")
    ap.add_argument("--last", required=True, help="Last hotswap dir (mod/run/devworld/classes-last)")
    args = ap.parse_args()

    t0 = time.perf_counter()
    classes_dir = Path(args.classes)
    boot_dir = Path(args.boot)
    last_dir = Path(args.last)

    if not boot_dir.exists():
        print("HOTSWAP REFUSED: no boot class baseline (server booted before hotswap tracking); restart required")
        return 4

    cur_map = collect_classes(classes_dir)
    boot_map = collect_classes(boot_dir)
    last_map = collect_classes(last_dir) if last_dir.exists() else boot_map

    changed = []
    for rel, cur_path in sorted(cur_map.items()):
        cur_bytes = cur_path.read_bytes()
        prev_path = last_map.get(rel)
        if prev_path is not None and sha256_bytes(prev_path.read_bytes()) == sha256_bytes(cur_bytes):
            continue
        if rel not in boot_map:
            cls_name = rel[:-6].replace("/", ".")
            print(f"HOTSWAP FALLBACK (schema change): new class added since boot: {cls_name}")
            return 4
        boot_bytes = boot_map[rel].read_bytes()
        reason = diff_schema(boot_bytes, cur_bytes)
        if reason is not None:
            print(f"HOTSWAP FALLBACK (schema change): {reason}")
            return 4
        changed.append((rel, cur_bytes))

    for rel in sorted(boot_map.keys()):
        if rel not in cur_map:
            cls_name = rel[:-6].replace("/", ".")
            print(f"HOTSWAP FALLBACK (schema change): class removed since boot: {cls_name}")
            return 4

    if not changed:
        print(f"HOTSWAP OK: 0 classes changed ({time.perf_counter() - t0:.2f} s)")
        return 0

    try:
        client = JdwpClient(args.host, args.port)
    except Exception as ex:
        print(f"HOTSWAP FALLBACK (JDWP connect failed on {args.host}:{args.port}: {ex})")
        return 4

    try:
        client.fetch_id_sizes()
        redefs = []
        redef_names = []
        unloaded_names = []
        for rel, cur_bytes in changed:
            internal = rel[:-6]
            jni_sig = f"L{internal};"
            cls_name = internal.replace("/", ".")
            type_ids = client.classes_by_signature(jni_sig)
            if not type_ids:
                unloaded_names.append(cls_name)
            else:
                for tid in type_ids:
                    redefs.append((tid, cur_bytes))
                redef_names.append(cls_name)

        if redefs:
            err = client.redefine_classes(redefs)
            if err != 0:
                desc = JDWP_ERRORS.get(err, f"ERROR_{err}")
                print(f"HOTSWAP FALLBACK (JDWP RedefineClasses error {err} {desc} for {', '.join(redef_names)})")
                return 4
    finally:
        client.close()

    # Persist new baseline into classes-last
    last_dir.mkdir(parents=True, exist_ok=True)
    for rel, cur_bytes in changed:
        dst = last_dir / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(cur_bytes)

    dt = time.perf_counter() - t0
    parts = []
    if redef_names:
        parts.append(f"redefined {len(redef_names)} loaded class(es) ({', '.join(redef_names)})")
    if unloaded_names:
        parts.append(f"updated {len(unloaded_names)} unloaded class(es) on disk ({', '.join(unloaded_names)})")
    print(f"HOTSWAP OK: {'; '.join(parts)} over JDWP in {dt:.2f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
