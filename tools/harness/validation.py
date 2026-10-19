"""Tool validation: the offline tools' predictions against what the real game produced.

* World: exact heights (AtlasGround: what the camera planner, the feature index and the far field believe) and seeded
  landmarks, offline (native/java/mod-integration/harness HeightSampleHarness) vs online (/nirncraft-qa sample).
* Lab vs live: an offline lab render (atlas_render) and a live capture of the same place, sun and weather (twins);
  compared in the overview.

Offline answers are cached per seed + world-generation fingerprint + request (.work/harness/validation/).
"""
import hashlib
import json
import os
import subprocess
import tempfile
from pathlib import Path

import cameras

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / ".work/harness/validation"


def offline(requests, seed, log=print):
    """{request id: {"points": [...], "landmarks": [...]}} for [{"id", "x", "z", "radius", "step"}]."""
    key = hashlib.sha256(json.dumps(sorted(requests, key=lambda r: r["id"]), sort_keys=True).encode()).hexdigest()[:10]
    path = CACHE / f"{seed}-{cameras.worldgen_hash()}-{key}.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    CACHE.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        req, out = Path(tmp) / "requests.json", Path(tmp) / "out.json"
        req.write_text(json.dumps(requests), encoding="utf-8")
        env = dict(os.environ, HARNESS_JAVA_OPTS=f"-Dharness.requests={req}")
        cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
               str(ROOT / "native/java/mod-integration/harness/run_exact_harness.ps1"), "-Main", "HeightSampleHarness",
               "-Out", str(out), "-Seed", str(seed), "-Threads", "4"]
        if cameras._sandbox_stale():
            cmd.append("-Rebuild")
        r = subprocess.run(cmd, cwd=ROOT, env=env, capture_output=True, text=True,
                           creationflags=0x00004000 if os.name == "nt" else 0)
        if r.returncode or not out.exists():
            raise RuntimeError("offline height samples failed:\n" + (r.stdout + r.stderr)[-1500:])
        data = json.loads(out.read_text(encoding="utf-8"))
    log(f"  validation: offline samples for {len(requests)} request(s)")
    path.write_text(json.dumps(data), encoding="utf-8")
    return data


def compare_world(online, predicted):
    """Numbers: how far the offline heights are from the generated surface, and whether the landmarks agree."""
    pred = {(p["x"], p["z"]): p for p in predicted.get("points", [])}
    diffs = []
    for p in online.get("points", []):
        q = pred.get((p["x"], p["z"]))
        if not q:
            continue
        # the heightmap's value is the first free block above the ground; offline terrain is the ground height
        ground = p.get("oceanFloor", p.get("motionNoLeaves")) - 1
        diffs.append(ground - int(q["terrain"] // 1))
    m = {}
    if diffs:
        ad = sorted(abs(d) for d in diffs)
        m["heightPoints"] = len(diffs)
        m["heightMedianErr"] = ad[len(ad) // 2]
        m["heightMaxErr"] = ad[-1]
        m["heightWithin2"] = round(100 * sum(1 for d in ad if d <= 2) / len(ad), 1)
        m["heightBias"] = round(sum(diffs) / len(diffs), 2)
    on = {(l["kind"], l["x"], l["z"]) for l in online.get("landmarks", [])}
    off = {(l["kind"], l["x"], l["z"]) for l in predicted.get("landmarks", [])}
    m["landmarksOnline"], m["landmarksOffline"] = len(on), len(off)
    m["landmarkMismatch"] = len(on ^ off)
    biomes = {}
    for p in online.get("points", []):
        biomes[p.get("biome", "?")] = biomes.get(p.get("biome", "?"), 0) + 1
    m["biomes"] = len(biomes)
    flags = []
    if diffs and m["heightWithin2"] < 80:
        flags.append(f"offline heights off: only {m['heightWithin2']}% within 2 blocks (bias {m['heightBias']})")
    if m["landmarkMismatch"]:
        flags.append(f"{m['landmarkMismatch']} landmark(s) differ offline vs online")
    m["problems"] = len(flags)
    return m, flags
