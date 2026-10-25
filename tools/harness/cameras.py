"""Planned cameras: shots for any named place, made outside Minecraft on the exact terrain of a seed (the mod's own
com.nirncraft.qa.CameraPlanner through native/java/mod-integration/harness CameraPlanHarness: the same planner the
in-game /nirncraft-tour uses). Plans are cached per seed and per world-generation fingerprint, so they are made again
by themselves when terrain code or data changes, and never reused across seeds.

    .work/harness/cameras/<seed>-<worldgen hash>.json   {place id: {"ground", "water", "aerial": [...], "approach": [...]}}
"""
import hashlib
import json
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / ".work/harness/cameras"
WORLDGEN = ["mod/src/main/resources/data/nirncraft/worldgen", "mod/src/main/resources/nirncraft/tamriel",
            "mod/src/main/java/com/nirncraft/tamriel", "mod/src/main/java/com/nirncraft/worldgen",
            "mod/src/main/java/com/nirncraft/worldplan", "mod/src/main/java/com/nirncraft/qa/CameraPlanner.java",
            "mod/src/main/java/com/nirncraft/atlas/AtlasGround.java"]
SANDBOX_INPUT = ROOT / "mod/src/main/java"


def worldgen_hash():
    """The world-generation fingerprint: the files' paths and CONTENTS (their modification times used to be in it, and a git
    worktree's checkout times differ from every other worktree's: each one launching the shared test instance made its own
    kept world and deleted the others', the player's saved spawn and the world's atlas data with it, on an unchanged generator)."""
    h = hashlib.sha256()
    for rel in WORLDGEN:
        p = ROOT / rel
        files = [p] if p.is_file() else sorted(f for f in p.rglob("*") if f.is_file())
        for f in files:
            h.update(f"{f.relative_to(ROOT).as_posix()}:{f.stat().st_size}:".encode())
            h.update(hashlib.sha256(f.read_bytes()).digest())
    return h.hexdigest()[:12]


def plan_path(seed):
    return CACHE / f"{seed}-{worldgen_hash()}.json"


def load(seed):
    p = plan_path(seed)
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


def _sandbox_stale():
    tag = ROOT.name
    classes = Path(os.environ.get("TEMP", "")) / f"nirncraft-atlas-sandbox-{tag}/classes/com/nirncraft/qa/CameraPlanner.class"
    if not classes.exists():
        return True
    newest = max(f.stat().st_mtime for f in SANDBOX_INPUT.rglob("*.java"))
    return newest > classes.stat().st_mtime


def ensure(place_ids, seed, places, threads=6, log=print):
    """Plans for every id in place_ids (missing ones only); returns the full plan for the seed."""
    plan = load(seed)
    want = [i for i in dict.fromkeys(place_ids) if i not in plan and i in places]
    if not want:
        return plan
    CACHE.mkdir(parents=True, exist_ok=True)
    for old in CACHE.glob(f"{seed}-*.json"):  # plans of an older world generation are wrong now
        if old != plan_path(seed):
            old.unlink()
    with tempfile.TemporaryDirectory() as tmp:
        targets, out = Path(tmp) / "targets.json", Path(tmp) / "plan.json"
        targets.write_text(json.dumps([{"id": i, "x": places[i]["x"], "z": places[i]["z"],
                                        "radius": places[i].get("radius", 40)} for i in want]), encoding="utf-8")
        env = dict(os.environ, HARNESS_JAVA_OPTS=f"-Dharness.targets={targets}")
        cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
               str(ROOT / "native/java/mod-integration/harness/run_exact_harness.ps1"), "-Main", "CameraPlanHarness",
               "-Out", str(out), "-Seed", str(seed), "-Threads", str(threads)]
        if _sandbox_stale():
            cmd.append("-Rebuild")
            log("  cameras: rebuilding the offline mod classes (mod code changed)")
        r = subprocess.run(cmd, cwd=ROOT, env=env, capture_output=True, text=True,
                           creationflags=0x00004000 if os.name == "nt" else 0)  # below normal: never starve the game
        if r.returncode or not out.exists():
            raise RuntimeError("camera planning failed:\n" + (r.stdout + r.stderr)[-1500:])
        new = json.loads(out.read_text(encoding="utf-8"))
        line = next((l for l in r.stdout.splitlines() if l.startswith("camera plan:")), "")
        log(f"  {line or f'cameras: planned {len(want)} place(s)'}")
    new.pop("_seed", None)
    plan.update(new)
    plan_path(seed).write_text(json.dumps(plan), encoding="utf-8")
    return plan


def shots(plan, place_id, kind="aerial"):
    """Candidate poses best first: [(camera [x,y,z], look [x,y,z])]."""
    entry = plan.get(place_id) or {}
    return [(p["camera"], p["look"]) for p in entry.get(kind, [])]
