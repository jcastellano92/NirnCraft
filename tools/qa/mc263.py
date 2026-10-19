"""Locate the Minecraft 26.3 reference jars for offline audits (read only; never builds or downloads anything).

Search order (first existing wins per kind):
  * $NIRNCRAFT_MC263_DIR (a folder holding client.jar and/or minecraft-patched-*.jar),
  * toolchains/ref/mc26.3 beside the checkout (any parent of the repo, so worktrees find it too),
  * the moddev artifacts of a 26.3 build: mod/build/moddev/artifacts and E:/NirnCraft-scratch/migration-26.3/build/moddev/artifacts.

client_jar()   a jar with assets/minecraft (vanilla models, blockstates, textures, items definitions)
sources_jar()  minecraft-patched-*-sources.jar (Java sources of the game, NeoForge-patched)
class_jar()    a jar with the game classes (client.jar or the patched jar)
Every function returns a Path or None; missing() says where it looked.
"""
from __future__ import annotations

import os
import struct
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _dirs():
    env = os.environ.get("NIRNCRAFT_MC263_DIR")
    if env:
        yield Path(env)
    for parent in [ROOT, *ROOT.parents]:
        yield parent / "toolchains" / "ref" / "mc26.3"
    yield ROOT / "mod" / "build" / "moddev" / "artifacts"
    yield Path("E:/NirnCraft-scratch/migration-26.3/build/moddev/artifacts")


def _first(patterns):
    for d in _dirs():
        if not d.is_dir():
            continue
        for pat in patterns:
            hits = sorted(d.glob(pat))
            if hits:
                return hits[-1]
    return None


def client_jar():
    return _first(["client.jar", "minecraft-patched-26.3*-merged.jar", "minecraft-patched-26.3*[0-9].jar"])


def sources_jar():
    return _first(["minecraft-patched-26.3*-sources.jar"])


def class_jar():
    return _first(["client.jar", "minecraft-patched-26.3*[0-9].jar", "minecraft-patched-26.3*-merged.jar"])


def missing():
    return ("Minecraft 26.3 reference jars not found (looked in $NIRNCRAFT_MC263_DIR, toolchains/ref/mc26.3 beside the "
            "checkout, mod/build/moddev/artifacts, E:/NirnCraft-scratch/migration-26.3/build/moddev/artifacts)")


def read(jar, name):
    """Bytes of one entry of a jar, or None."""
    try:
        with zipfile.ZipFile(jar) as z:
            return z.read(name)
    except (OSError, KeyError, zipfile.BadZipFile):
        return None


def static_fields(class_bytes):
    """[(name, descriptor)] of the static fields of a .class file (pure Python, no javap)."""
    b, pos = class_bytes, 10
    count = struct.unpack(">H", b[8:10])[0]
    utf8, i = {}, 1
    while i < count:
        tag = b[pos]
        if tag == 1:
            n = struct.unpack(">H", b[pos + 1:pos + 3])[0]
            utf8[i] = b[pos + 3:pos + 3 + n].decode("utf-8", "replace")
            pos += 3 + n
        elif tag in (7, 8, 16, 19, 20):
            pos += 3
        elif tag == 15:
            pos += 4
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        else:
            raise ValueError(f"constant pool tag {tag}")
        i += 1
    pos += 6
    pos += 2 + 2 * struct.unpack(">H", b[pos:pos + 2])[0]  # interfaces
    out = []
    nfields = struct.unpack(">H", b[pos:pos + 2])[0]
    pos += 2
    for _ in range(nfields):
        flags, name, desc, attrs = struct.unpack(">HHHH", b[pos:pos + 8])
        pos += 8
        for _ in range(attrs):
            pos += 6 + struct.unpack(">I", b[pos + 2:pos + 6])[0]
        if flags & 0x0008:
            out.append((utf8.get(name), utf8.get(desc)))
    return out
