"""Builds dist/Skycraft-client-instance.zip (Prism Launcher / MultiMC) locally, without packwiz.

Usage: python3 tools/build_prism_instance.py [--core-jar PATH]

* The instance is built from the committed pack state: every file listed in pack/index.toml (configs, defaultconfigs,
  mod metadata). Each indexed file's hash is checked against the index first, so a stale index fails the build
  (run tools/refresh_packwiz.py after editing pack/).
* Mods and shader packs are downloaded from their .pw.toml metadata (Modrinth URL, or the CurseForge CDN for
  CurseForge-only files) and their sha1/sha512 is verified every time, including when they come from dist/.cache.
* Skycraft Core is not part of the committed pack. It comes from --core-jar, or else the newest jar in mod/build/libs
  (run `./gradlew build` in mod/ first).
"""
import argparse
import glob
import hashlib
import os
import shutil
import sys
import tomllib
import urllib.parse
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
PACK_DIR = os.path.join(ROOT, "pack")
DIST_DIR = os.path.join(ROOT, "dist")
CACHE_DIR = os.path.join(DIST_DIR, ".cache")
WORK_DIR = os.path.join(DIST_DIR, "tmp_prism_build")
INST_DIR = os.path.join(WORK_DIR, "Skycraft")
MC_DIR = os.path.join(INST_DIR, ".minecraft")
OUT_ZIP = os.path.join(DIST_DIR, "Skycraft-client-instance.zip")

MC_VERSION = "1.20.1"
FORGE_VERSION = "47.4.10"
# Heap comes from Min/MaxMemAlloc (Prism adds -Xms/-Xmx itself). -Dessential.autoUpdate=false keeps the Essential
# loader from replacing the pinned Essential version (read by Essential Loader stage2 as "<mod slug>.autoUpdate").
JVM_ARGS = ("-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions "
            "-XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M "
            "-Dessential.autoUpdate=false")

HEADERS = {"User-Agent": "skycraft-pack-tools/1.0 (+https://github.com/jcastellano92/Skycraft)"}


def file_hash(path, fmt):
    h = hashlib.new(fmt)
    with open(path, "rb") as fp:
        for chunk in iter(lambda: fp.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def curseforge_url(file_id, filename):
    # CurseForge CDN layout: /files/<id // 1000>/<id % 1000>/<file name>, e.g. 5100034 -> /files/5100/34/.
    fid = int(file_id)
    return f"https://edge.forgecdn.net/files/{fid // 1000}/{fid % 1000}/{urllib.parse.quote(filename)}"


def download(target_path, url, expected_hash, hash_format):
    """Copies the file from dist/.cache (downloading it first if needed) and verifies its hash."""
    os.makedirs(CACHE_DIR, exist_ok=True)
    os.makedirs(os.path.dirname(target_path), exist_ok=True)
    cache_key = hashlib.sha256(url.encode()).hexdigest()[:16] + "_" + os.path.basename(target_path)
    cache_path = os.path.join(CACHE_DIR, cache_key)
    cached = os.path.exists(cache_path)
    if cached and file_hash(cache_path, hash_format) != expected_hash.lower():
        print(f"  cached copy of {os.path.basename(target_path)} has the wrong hash; downloading again")
        os.remove(cache_path)
        cached = False
    if not cached:
        tmp = cache_path + ".tmp"
        with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS)) as resp, open(tmp, "wb") as out:
            shutil.copyfileobj(resp, out)
        got = file_hash(tmp, hash_format)
        if got != expected_hash.lower():
            os.remove(tmp)
            raise ValueError(f"hash mismatch for {url}: expected {expected_hash}, got {got}")
        os.replace(tmp, cache_path)
    shutil.copyfile(cache_path, target_path)
    if file_hash(target_path, hash_format) != expected_hash.lower():
        raise ValueError(f"hash mismatch after copying {target_path}")
    return cached


def find_core_jar(arg):
    if arg:
        if not os.path.isfile(arg):
            sys.exit(f"--core-jar {arg} does not exist")
        return arg
    jars = [j for j in glob.glob(os.path.join(ROOT, "mod", "build", "libs", "*.jar"))
            if not j.endswith(("-sources.jar", "-slim.jar"))]
    if not jars:
        sys.exit("No Skycraft Core jar: run `./gradlew build` in mod/ or pass --core-jar PATH")
    return max(jars, key=os.path.getmtime)


def read_index():
    with open(os.path.join(PACK_DIR, "pack.toml"), "rb") as fp:
        pack = tomllib.load(fp)
    index_path = os.path.join(PACK_DIR, pack["index"]["file"])
    if file_hash(index_path, pack["index"]["hash-format"]) != pack["index"]["hash"]:
        sys.exit("pack/index.toml doesn't match the hash in pack/pack.toml: run tools/refresh_packwiz.py")
    with open(index_path, "rb") as fp:
        index = tomllib.load(fp)
    fmt = index.get("hash-format", "sha256")
    for entry in index.get("files", []):
        path = os.path.join(PACK_DIR, entry["file"])
        if not os.path.isfile(path):
            sys.exit(f"pack/index.toml lists {entry['file']}, which doesn't exist")
        if file_hash(path, entry.get("hash-format", fmt)) != entry["hash"]:
            sys.exit(f"pack/{entry['file']} doesn't match pack/index.toml: run tools/refresh_packwiz.py")
    return index["files"]


def build_prism_package(core_jar_arg):
    print("=== Building the Skycraft instance for Prism Launcher ===")
    core_jar = find_core_jar(core_jar_arg)
    entries = read_index()

    if os.path.exists(WORK_DIR):
        shutil.rmtree(WORK_DIR)
    os.makedirs(MC_DIR, exist_ok=True)

    print("\n[1/4] Copying indexed pack files (configs, defaults)...")
    tasks = []
    for entry in entries:
        rel = entry["file"]
        src = os.path.join(PACK_DIR, rel)
        if not entry.get("metafile"):
            dest = os.path.join(MC_DIR, rel)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            shutil.copyfile(src, dest)
            continue
        with open(src, "rb") as fp:
            meta = tomllib.load(fp)
        if meta.get("side", "both") == "server":
            continue
        dl = meta.get("download", {})
        url = dl.get("url")
        if not url:
            cf = meta.get("update", {}).get("curseforge", {})
            if not cf.get("file-id"):
                sys.exit(f"{rel} has neither a download url nor a CurseForge file id")
            url = curseforge_url(cf["file-id"], meta["filename"])
        if not dl.get("hash") or not dl.get("hash-format"):
            sys.exit(f"{rel} has no download hash")
        target = os.path.join(MC_DIR, os.path.dirname(rel), meta["filename"])
        tasks.append((meta.get("name", meta["filename"]), target, url, dl["hash"], dl["hash-format"]))

    print(f"\n[2/4] Installing Skycraft Core from {core_jar}")
    os.makedirs(os.path.join(MC_DIR, "mods"), exist_ok=True)
    shutil.copyfile(core_jar, os.path.join(MC_DIR, "mods", "skycraft-core.jar"))

    print(f"\n[3/4] Downloading {len(tasks)} mods and shader packs (hash-checked)...")

    def worker(task):
        name, target, url, h, fmt = task
        try:
            cached = download(target, url, h, fmt)
            print(f"  [OK] {name}{' (cache)' if cached else ''}")
            return None
        except Exception as e:
            print(f"  [FAIL] {name}: {e}")
            return f"{name}: {e}"

    with ThreadPoolExecutor(max_workers=8) as pool:
        failed = [r for r in pool.map(worker, tasks) if r]
    if failed:
        print(f"\n{len(failed)} downloads failed:")
        for f in failed:
            print("  -", f)
        sys.exit(1)

    print("\n[4/4] Writing the Prism instance...")
    with open(os.path.join(INST_DIR, "instance.cfg"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"""InstanceType=OneSix
name=Skycraft
iconKey=default
notes=Skycraft: The Elder Scrolls V Skyrim total conversion for Minecraft {MC_VERSION} Forge {FORGE_VERSION}
OverrideJavaArgs=true
JvmArgs={JVM_ARGS}
OverrideMemory=true
MinMemAlloc=2048
MaxMemAlloc=6144
""")
    with open(os.path.join(INST_DIR, "mmc-pack.json"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"""{{
  "components": [
    {{"uid": "net.minecraft", "version": "{MC_VERSION}", "important": true}},
    {{"uid": "net.minecraftforge", "version": "{FORGE_VERSION}"}}
  ],
  "formatVersion": 1
}}
""")

    if os.path.exists(OUT_ZIP):
        os.remove(OUT_ZIP)
    with zipfile.ZipFile(OUT_ZIP, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _, files in os.walk(INST_DIR):
            for f in files:
                full = os.path.join(root, f)
                zf.write(full, os.path.relpath(full, WORK_DIR))
    shutil.rmtree(WORK_DIR, ignore_errors=True)
    print(f"\nDone: {OUT_ZIP} ({os.path.getsize(OUT_ZIP) / (1024 * 1024):.1f} MB)")
    print("In Prism Launcher: Add Instance -> Import -> choose this zip.")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--core-jar", help="Skycraft Core jar (default: newest jar in mod/build/libs)")
    build_prism_package(ap.parse_args().core_jar)
