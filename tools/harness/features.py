"""Generated-feature index: representative spots of every generated feature type for a seed, found outside Minecraft
(native/java/mod-integration/harness FeatureIndexHarness on the seeded terrain): every surface biome the world's biome
source places (interior spots, spread across regions, on land unless the biome is aquatic), every seeded natural
landmark kind and formation, every minor site, stash and baked scene-plan kind / anchor. feature_scenes.py turns it
into scenes. Cached per seed and world-generation fingerprint (cameras.worldgen_hash plus the landmark / explore /
scatter code and data), so it is made again by itself when any of them changes.

    .work/harness/features/<seed>-<hash>.json   {"_seed", "biomes": {id: [spot]}, "landmarks": {kind: [spot]},
                                                  "sites": {kind: [spot]}, "_found": {group: {kind: count}}}
"""
import hashlib
import json
import os
import subprocess
import tempfile
from pathlib import Path

import cameras

ROOT = cameras.ROOT
CACHE = ROOT / ".work/harness/features"
HARNESS = ROOT / "native/java/mod-integration/harness"
EXTRA = ["mod/src/main/java/com/nirncraft/landmarks", "mod/src/main/java/com/nirncraft/explore",
         "mod/src/main/java/com/nirncraft/scatter", "mod/src/main/resources/nirncraft/landmarks",
         "mod/src/main/resources/nirncraft/explore", "mod/src/main/resources/nirncraft/scatter",
         "mod/src/main/resources/nirncraft/baked/sceneplan.bin",
         "native/java/mod-integration/harness/com/nirncraft/atlas/FeatureIndexHarness.java"]
GROUPS = ("biomes", "landmarks", "sites")


def index_hash():
    h = hashlib.sha256(cameras.worldgen_hash().encode())
    for rel in EXTRA:
        p = ROOT / rel
        if not p.exists():
            continue
        for f in [p] if p.is_file() else sorted(f for f in p.rglob("*") if f.is_file()):
            st = f.stat()
            h.update(f"{f.relative_to(ROOT).as_posix()}:{st.st_size}:{int(st.st_mtime)}".encode())
    return h.hexdigest()[:12]


def index_path(seed):
    return CACHE / f"{seed}-{index_hash()}.json"


def load(seed):
    """The index for the seed and the current world generation, or {} (nothing is run)."""
    p = index_path(seed)
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


def latest(seed):
    """The newest cached index for the seed, current or not (cheap: no fingerprint; for the catalog), or {}."""
    files = sorted(CACHE.glob(f"{seed}-*.json"), key=lambda f: f.stat().st_mtime, reverse=True)
    for f in files:
        try:
            return json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
    return {}


def ensure(seed, threads=8, per=6, force=False, log=print):
    """The index for the seed, made (about 10 s plus a mod-class rebuild when mod code changed) when the cached one is
    missing or belongs to an older world generation."""
    idx = {} if force else load(seed)
    if idx:
        return idx
    CACHE.mkdir(parents=True, exist_ok=True)
    want = index_path(seed)
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "features.json"
        env = dict(os.environ, HARNESS_JAVA_OPTS=f"-Dharness.per={per}")
        cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(HARNESS / "run_exact_harness.ps1"),
               "-Main", "FeatureIndexHarness", "-Out", str(out), "-Seed", str(seed), "-Threads", str(threads)]
        if cameras._sandbox_stale():
            cmd.append("-Rebuild")
            log("  features: rebuilding the offline mod classes (mod code changed)")
        r = subprocess.run(cmd, cwd=ROOT, env=env, capture_output=True, text=True,
                           creationflags=0x00004000 if os.name == "nt" else 0)  # below normal: never starve the game
        if r.returncode or not out.exists():
            hint = ""
            if "atlas_core.dll is missing" in r.stdout + r.stderr:
                hint = ("\n(the sandbox build wants native/build/Release/atlas_core.dll: native/build.ps1, or copy the "
                        "shipped mod/src/main/resources/natives/windows-x86_64/atlas_core.dll there)")
            raise RuntimeError("feature index failed:\n" + (r.stdout + r.stderr)[-1500:] + hint)
        line = next((l for l in r.stdout.splitlines() if l.startswith("feature index:")), "")
        log(f"  {line or 'features: index made'}")
        idx = json.loads(out.read_text(encoding="utf-8"))
    for old in CACHE.glob(f"{seed}-*.json"):  # an index of an older world generation is wrong now
        if old != want:
            old.unlink()
    want.write_text(json.dumps(idx), encoding="utf-8")
    return idx
