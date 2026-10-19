"""Live loop for the SkycraftAether pack in the running disposable preview (no restart, no rebuild).

    py -3 shaderpack/tools/pack_live.py [--launch] [--close] [--cams helgen-near-smoke,mine-crystal-chamber]
                                        [--tag NAME] [--time 6000] [--weather clear|rain|thunder] [--no-sync] [--off]

--launch starts the disposable preview if it is not running and waits for a fresh, ready world; --close shuts it down
cleanly at the end (nothing is left running). The live lease (.work/lease/live.json) is taken for the run and
released after --close.

1. syncs this worktree's pack into the instance's shaderpacks/SkycraftAether (only changed files),
2. reloads it in the running game (`shaders off` / `shaders on` through tools/dev/live_world.py actions, which owns the
   harness lock, readiness and busy checks),
3. prints only the shader compile/link errors Iris logged since the reload,
4. visits approved cameras (tools/qa/visual-cameras.json) and captures each: `TAG-CAMERA.png` under the instance's
   build/autotest-screenshots (with --off, also a shaders-off frame for comparison).

Run from the main checkout (F:\\Projects\\Personal\\Skycraft\\Skycraft). Needs a running, ready preview
(`live_world.py status`: client.fresh) and the live lease (.work/lease/live.json) held by this workstream.
"""
import argparse
import filecmp
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
PACK = HERE.parent / "SkycraftAether"
MAIN = Path(os.environ.get("SKYCRAFT_MAIN", r"F:\Projects\Personal\Skycraft\Skycraft"))
INSTANCE = Path(os.environ["APPDATA"]) / "PrismLauncher/instances/Skycraft-26.3-autotest/.minecraft"
DEST = INSTANCE / "shaderpacks/SkycraftAether"
LOG = INSTANCE / "logs/latest.log"
SHOTS = INSTANCE / "build/autotest-screenshots"


DEST_ZIP = INSTANCE / "shaderpacks/SkycraftAether.zip"


def sync():
    """Build this tree's pack (glslang-validated zip, as shipped) and install it the way players get it: when the
    instance selects SkycraftAether.zip (the modpack default) the zip is replaced; a loose-folder instance gets the
    folder synced."""
    iris = INSTANCE / "config/iris.properties"
    if iris.exists() and "shaderPack=SkycraftAether.zip" in iris.read_text(encoding="utf-8", errors="replace"):
        r = subprocess.run([sys.executable, "-I", str(HERE.parent / "build_pack.py")], capture_output=True, text=True)
        if r.returncode:
            raise SystemExit("pack build failed:\n" + (r.stdout + r.stderr)[-1500:])
        built = HERE.parent / "build/SkycraftAether.zip"
        if DEST_ZIP.exists() and filecmp.cmp(built, DEST_ZIP, shallow=False):
            return 0
        shutil.copy2(built, DEST_ZIP)
        return 1
    changed = 0
    for src in PACK.rglob("*"):
        if src.is_dir():
            continue
        dst = DEST / src.relative_to(PACK)
        if dst.exists() and filecmp.cmp(src, dst, shallow=False):
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        changed += 1
    for dst in list(DEST.rglob("*")):  # files removed from the pack
        if dst.is_file() and not (PACK / dst.relative_to(DEST)).exists():
            dst.unlink()
            changed += 1
    return changed


def actions(lines, timeout=600):
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
        path = f.name
    try:
        r = subprocess.run([sys.executable, "tools/dev/live_world.py", "actions", path], cwd=MAIN,
                           capture_output=True, text=True, timeout=timeout)
        if r.returncode:
            raise SystemExit("live actions failed:\n" + (r.stdout + r.stderr)[-1500:])
    finally:
        os.unlink(path)


ERR = re.compile(r"(error|failed|exception|could not|unable to)", re.I)
GLSL = re.compile(r"^\s*(ERROR|WARNING)?:?\s*\d+:\d+", re.I)


def shader_errors(since):
    if not LOG.exists():
        return ["(no latest.log)"]
    with open(LOG, "rb") as f:
        f.seek(since)
        text = f.read().decode("utf-8", "replace")
    out = []
    for line in text.splitlines():
        low = line.lower()
        relevant = ("iris" in low or "shader" in low or "glsl" in low or "program" in low) and ERR.search(line)
        if relevant or GLSL.match(line):
            out.append(line.strip()[:300])
    return out


def camera_lines(name, weather, day_time):
    cams = json.loads((MAIN / "tools/qa/visual-cameras.json").read_text(encoding="utf-8"))
    cams = cams.get("cameras", cams)
    c = cams[name] if isinstance(cams, dict) else next(x for x in cams if x.get("name") == name)
    (x, y, z), (tx, ty, tz) = c["position"], c["lookAt"]
    pre = [f"command time set {day_time}"] if day_time else []
    pre += [f"command weather {weather}"] if weather else []  # Skycraft's own weather may refuse vanilla's
    return pre + [f"camera {x} {y} {z} {tx} {ty} {tz}", "settle 1800"]


def live(*args, timeout=600):
    r = subprocess.run([sys.executable, "tools/dev/live_world.py", *args], cwd=MAIN, capture_output=True, text=True,
                       timeout=timeout)
    return r.returncode, r.stdout + r.stderr


def client_state():
    code, out = live("status", timeout=120)
    try:
        return json.loads(out[out.index("{"):])
    except ValueError:
        raise SystemExit("status unreadable: " + out[-800:])


LEASE = MAIN / ".work/lease/live.json"
OWNER = "aether/shader workstream (pack_live.py)"


def take_lease():
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    if LEASE.exists():
        held = json.loads(LEASE.read_text(encoding="utf-8"))
        age = time.time() - time.mktime(time.strptime(held.get("heartbeat", now), "%Y-%m-%dT%H:%M:%SZ")) + time.timezone
        if not held.get("owner", "").startswith("aether/shader") and age < 600:
            raise SystemExit(f"live lease held by {held.get('owner')} (heartbeat {int(age)} s ago): wait your turn")
    LEASE.parent.mkdir(parents=True, exist_ok=True)
    LEASE.write_text(json.dumps({"owner": OWNER, "system": "claude-code", "job": "pack live loop",
                                 "instance": "Skycraft-26.3-autotest", "heartbeat": now}), encoding="utf-8")


def launch_and_wait(limit=900):
    st = client_state()
    if not st.get("running"):
        code, out = live("launch", timeout=180)
        if code:
            raise SystemExit("launch failed: " + out[-800:])
        print("launched; waiting for the world")
    t0 = time.monotonic()
    gone = 0  # consecutive polls without the game process (one miss happens while Prism hands over)
    while time.monotonic() - t0 < limit:
        st = client_state()
        c = st.get("client", {})
        remote = c.get("connected") and c.get("remote") and not c.get("integratedServer")  # TCP multiplayer
        if c.get("fresh") and (st.get("serverReady") or (remote and c.get("worldLoaded"))):
            print(f"world ready after {time.monotonic() - t0:.0f} s")
            return
        gone = gone + 1 if not st.get("running") else 0
        if gone >= 10 and time.monotonic() - t0 > 60:
            raise SystemExit("client exited while loading; see logs/latest.log")
        time.sleep(3)
    raise SystemExit("world not ready in time; client left running for inspection")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cams", default="helgen-near-smoke")
    ap.add_argument("--tag", default="aether")
    ap.add_argument("--time", default="")
    ap.add_argument("--weather", default="")
    ap.add_argument("--no-sync", action="store_true")
    ap.add_argument("--off", action="store_true", help="also capture each camera with shaders off")
    ap.add_argument("--visit", action="append", default=[],
                    help="extra pose NAME:x,z,heightAboveSurface,yaw,pitch[,weather] (repeatable)")
    ap.add_argument("--wait", type=int, default=0, help="extra ticks after settle before each visit capture (far tiles)")
    ap.add_argument("--launch", action="store_true", help="start the preview if needed and wait for the world")
    ap.add_argument("--close", action="store_true", help="close the preview cleanly at the end")
    a = ap.parse_args()
    take_lease()
    try:
        run(a)
    finally:
        if a.close:
            code, out = live("close", timeout=300)
            print("closed cleanly" if code == 0 else "close FAILED: " + out[-600:])
            if code == 0 and LEASE.exists():
                LEASE.unlink()


def run(a):
    if a.launch:
        launch_and_wait()
    if not a.no_sync:
        print(f"synced {sync()} changed file(s) into {DEST}")
    # a shader toggle checks readiness at the current pose, and the spawn pose may be buried: move to the first
    # requested pose before reloading
    first = []
    if a.visit:
        x, z, h, yaw, pitch, *w = a.visit[0].split(":", 1)[1].split(",")
        first = [f"visit {x} {z} {h} {yaw} {pitch} {w[0] if w else 'clear'}"]
    elif [c for c in a.cams.split(",") if c]:
        first = [l for l in camera_lines(a.cams.split(",")[0], "", "") if l.startswith("camera ")]
    since = LOG.stat().st_size if LOG.exists() else 0
    t0 = time.monotonic()
    actions(first + ["shaders off", "shaders on"])
    errs = shader_errors(since)
    print(f"reload acknowledged in {time.monotonic() - t0:.1f} s; {len(errs)} shader log line(s)")
    for e in errs[:40]:
        print("  " + e)
    lines = []
    shots = []
    for v in a.visit:
        name, spec = v.split(":", 1)
        x, z, h, yaw, pitch, *w = spec.split(",")
        lines += [f"visit {x} {z} {h} {yaw} {pitch} {w[0] if w else 'clear'}", "settle 1800"]
        lines += [f"wait {a.wait}"] if a.wait else []
        lines.append(f"capture {a.tag}-{name}")
        shots.append(f"{a.tag}-{name}")
        if a.off:
            lines += ["shaders off", "settle 600", f"capture {a.tag}-{name}-vanilla", "shaders on", "settle 1200"]
            shots.append(f"{a.tag}-{name}-vanilla")
    for cam in [c for c in a.cams.split(",") if c]:
        lines += camera_lines(cam, a.weather, a.time)
        lines.append(f"capture {a.tag}-{cam}")
        if a.off:
            lines += ["shaders off", "settle 600", f"capture {a.tag}-{cam}-vanilla", "shaders on", "settle 1200"]
    if lines:
        actions(lines, timeout=1800)
        for shot in shots:
            p = SHOTS / f"{shot}.png"
            print(("ok   " if p.exists() else "MISS ") + str(p))
        for cam in [c for c in a.cams.split(",") if c]:
            for suffix in ("", "-vanilla") if a.off else ("",):
                p = SHOTS / f"{a.tag}-{cam}{suffix}.png"
                print(("ok   " if p.exists() else "MISS ") + str(p))


if __name__ == "__main__":
    main()
