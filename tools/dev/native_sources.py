"""Which native sources each packed atlas_core was built from.

mod/src/main/resources/natives/manifest.json records, per platform folder (windows-x86_64, linux-x86_64, ...), the
hash of the native sources (native/src, native/shaders, native/include, native/CMakeLists.txt) the shipped library was
built from. The harness writes the Windows entry when it ships a fresh atlas_core (tools/harness/game.py sync_native),
tools/dev/build_linux_wsl.py the Linux one; tools/dev/check_natives.py --require-fresh fails when a platform's entry
differs from today's sources (a library a build behind: the ABI check alone cannot see it).

    py -3 tools/dev/native_sources.py            # today's hash and each platform's state
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "mod/src/main/resources/natives/manifest.json"
PARTS = ("native/src", "native/shaders", "native/include", "native/CMakeLists.txt")


def source_hash(root=ROOT):
    """A hash of every native source file's path and bytes (line endings normalised: a checkout's autocrlf must not
    count as a change)."""
    h = hashlib.sha256()
    files = []
    for part in PARTS:
        p = root / part
        files += [p] if p.is_file() else sorted(f for f in p.rglob("*") if f.is_file())
    for f in files:
        h.update(f.relative_to(root).as_posix().encode())
        h.update(f.read_bytes().replace(b"\r\n", b"\n"))
    return h.hexdigest()


def read_manifest():
    try:
        return json.loads(MANIFEST.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def record(platform_dir, digest=None):
    """Notes that the library in natives/<platform_dir> was built from `digest` (today's sources by default)."""
    m = read_manifest()
    m[platform_dir] = digest or source_hash()
    MANIFEST.write_text(json.dumps(m, indent=1, sort_keys=True) + "\n", encoding="utf-8")


def stale(platform_dir, digest=None):
    return read_manifest().get(platform_dir) != (digest or source_hash())


if __name__ == "__main__":
    now = source_hash()
    print(f"native sources {now[:12]}")
    for k, v in sorted(read_manifest().items()):
        print(f"  {k:16} built from {v[:12]}  {'current' if v == now else 'BEHIND: rebuild'}")
