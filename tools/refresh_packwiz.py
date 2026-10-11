"""Regenerates pack/index.toml and the index hash in pack/pack.toml, like `packwiz refresh`, without needing packwiz.

Usage: python3 tools/refresh_packwiz.py [--pack DIR] [--with-core]

* Paths resolve relative to this script, so it works from any working directory.
* Only pack content is indexed: config/**, defaultconfigs/**, mods/*.pw.toml, resourcepacks/*.pw.toml,
  shaderpacks/*.pw.toml, shaderpacks/*.zip.txt (a shader pack's option preset, read by Oculus from
  shaderpacks/<pack file name>.txt) and mods/*.jar. Everything else in the folder (RESOLVE_REPORT.md, README, editor files) is
  ignored.
* mods/skycraft-core.jar is a build output that is never committed. It is only indexed with --with-core, which the
  build (tools/prepare-build-pack.sh) uses on its throwaway copy of the pack.
* Hashes are SHA-256 of the raw bytes on disk. .gitattributes keeps pack/** byte-identical across OSes (LF), so the
  index matches what packwiz-installer downloads from GitHub.
"""
import argparse
import hashlib
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
CORE_JAR = "mods/skycraft-core.jar"


def wanted(rel):
    top, _, rest = rel.partition("/")
    if top in ("config", "defaultconfigs"):
        return bool(rest)
    if top in ("mods", "resourcepacks", "shaderpacks") and "/" not in rest:
        return (rest.endswith(".pw.toml") or (top == "mods" and rest.endswith(".jar"))
                or (top == "shaderpacks" and rest.endswith(".zip.txt")))
    return False


def sha256(path):
    with open(path, "rb") as fp:
        return hashlib.sha256(fp.read()).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", default=os.path.join(ROOT, "pack"), help="pack folder (default: <repo>/pack)")
    ap.add_argument("--with-core", action="store_true", help=f"also index {CORE_JAR} (build copies only)")
    args = ap.parse_args()
    pack_dir = os.path.abspath(args.pack)
    index_path = os.path.join(pack_dir, "index.toml")
    pack_path = os.path.join(pack_dir, "pack.toml")

    entries = []
    for root, dirs, files in os.walk(pack_dir):
        dirs.sort()
        for f in files:
            rel = os.path.relpath(os.path.join(root, f), pack_dir).replace("\\", "/")
            if not wanted(rel):
                continue
            if rel == CORE_JAR and not args.with_core:
                continue
            entries.append((rel, sha256(os.path.join(root, f)), rel.endswith(".pw.toml")))
    entries.sort(key=lambda e: e[0])
    if args.with_core and not any(rel == CORE_JAR for rel, _, _ in entries):
        raise SystemExit(f"--with-core given but {os.path.join(pack_dir, CORE_JAR)} does not exist")

    lines = ['hash-format = "sha256"', ""]
    for rel, digest, is_meta in entries:
        lines.append("[[files]]")
        lines.append(f'file = "{rel}"')
        lines.append(f'hash = "{digest}"')
        if is_meta:
            lines.append("metafile = true")
        lines.append("")
    with open(index_path, "wb") as fp:
        fp.write("\n".join(lines).encode("utf-8"))
    index_hash = sha256(index_path)

    with open(pack_path, "rb") as fp:
        pack = fp.read().decode("utf-8").replace("\r\n", "\n")
    # Only the [index] table's hash is ours to change.
    pack, n = re.subn(r'(\[index\][^\[]*?hash = ")[0-9a-f]+(")', rf"\g<1>{index_hash}\g<2>", pack, count=1)
    if n != 1:
        raise SystemExit(f"could not find the [index] hash in {pack_path}")
    with open(pack_path, "wb") as fp:
        fp.write(pack.encode("utf-8"))

    print(f"{index_path}: {len(entries)} files, index hash {index_hash}")


if __name__ == "__main__":
    main()
