"""Builds dist/NirnCraft-26.3.mrpack: the same pinned pack as the Prism zip (pack/ + the tested core jar), in Modrinth's
modpack format, so the Modrinth App (and Prism's Modrinth import) installs it with no setup on Windows and Linux.

    py -3 tools/build_mrpack.py --core-jar E:/NirnCraft-scratch/migration-26.3/build/libs/nirncraft-0.1.0.jar

Mods are referenced by their pinned download URLs (Modrinth CDN / GitHub releases) with sha1 + sha512 + size; files
come from dist/.cache (the Prism builder's verified cache) or are downloaded and verified against the pin. Configs, the
InfiniteATLAS shader pack (enabled by default in config/iris.properties) and the NirnCraft core jar (with its Windows
and Linux natives) go into overrides/. Java 25 is chosen by the launcher; memory is the player's setting (Modrinth packs
cannot set JVM arguments: recommend 6-8 GB in the description).
"""
import argparse
import hashlib
import json
import os
import sys
import tomllib
import urllib.request
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
PACK = os.path.join(ROOT, "pack")
CACHE = os.path.join(ROOT, "dist", ".cache")
ALLOWED = ("https://cdn.modrinth.com/", "https://github.com/", "https://raw.githubusercontent.com/", "https://gitlab.com/")


def digest(path, algo):
    h = hashlib.new(algo)
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(url, filename, hash_format, expected):
    key = hashlib.sha256(url.encode()).hexdigest()[:16] + "_" + filename  # same key as build_prism_instance.py
    path = os.path.join(CACHE, key)
    if not os.path.exists(path) or digest(path, hash_format) != expected.lower():
        os.makedirs(CACHE, exist_ok=True)
        print(f"  downloading {filename}")
        with urllib.request.urlopen(url) as r, open(path, "wb") as out:
            out.write(r.read())
        if digest(path, hash_format) != expected.lower():
            sys.exit(f"hash mismatch for {filename}")
    return path


def env(side):
    return {"client": "required" if side in ("both", "client") else "unsupported",
            "server": "required" if side in ("both", "server") else "unsupported"}


def build(core_jar, out):
    with open(os.path.join(PACK, "pack.toml"), "rb") as f:
        pack = tomllib.load(f)
    with open(os.path.join(PACK, pack["index"]["file"]), "rb") as f:
        index = tomllib.load(f)
    files, overrides = [], []
    for entry in index["files"]:
        rel = entry["file"]
        if rel.startswith("migration/"):
            continue  # build-time platform lock, not player content
        src = os.path.join(PACK, rel)
        if not entry.get("metafile"):
            overrides.append((src, "overrides/" + rel))
            continue
        with open(src, "rb") as f:
            meta = tomllib.load(f)
        dl = meta["download"]
        if not dl.get("url", "").startswith(ALLOWED):
            sys.exit(f"{rel}: {dl.get('url')} is not a Modrinth-allowed download host")
        path = fetch(dl["url"], meta["filename"], dl["hash-format"], dl["hash"])
        target = os.path.dirname(rel) + "/" + meta["filename"]
        files.append({"path": target, "hashes": {"sha1": digest(path, "sha1"), "sha512": digest(path, "sha512")},
                      "env": env(meta.get("side", "both")), "downloads": [dl["url"]], "fileSize": os.path.getsize(path)})
    overrides.append((core_jar, "overrides/mods/nirncraft-core.jar"))
    manifest = {
        "formatVersion": 1, "game": "minecraft", "versionId": pack.get("version", "0.1.0"),
        "name": pack.get("name", "NirnCraft"),
        "summary": "Tamriel in Minecraft 26.3: NirnCraft's world, its own distant-terrain renderer and shader pack. "
                   "Needs Java 25 (the launcher provides it); 6-8 GB of memory recommended.",
        "files": sorted(files, key=lambda f: f["path"]),
        "dependencies": {"minecraft": pack["versions"]["minecraft"], "neoforge": pack["versions"]["neoforge"]},
    }
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(manifest, indent=2))
        for src, arc in overrides:
            z.write(src, arc)
    print(f"wrote {out}: {len(files)} mods by reference, {len(overrides)} override files "
          f"({os.path.getsize(out) / 1e6:.1f} MB)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--core-jar", required=True)
    ap.add_argument("--output", default=os.path.join(ROOT, "dist", "NirnCraft-26.3.mrpack"))
    a = ap.parse_args()
    build(a.core_jar, a.output)


if __name__ == "__main__":
    main()
