"""A static camera watched for a few minutes (the MIDDLE loop for the quality controller and level 0's reach).

    py -3 tools/harness/atlas_static.py [--seconds 180] [--settle 30]

The client starts at the route's spawn (atlas_tools.ensure_spawn), the camera is put there and left alone; the passive
profile line (atlas-profile.log, every 2 s) is read back: per sample atlasMs, level 0's reach, the controller's block
(quality{tier, budget, gpu, atlas, scale, lod}). Verdict after `settle` seconds: atlasMs within +-15 % of its median, the
reach's range, the tier changes. A static camera must not cycle (10-26: 2.5 -> 30 ms every 30 s, reach 126 <-> 1208).
The measurement starts only after a fresh ready{ok} sample with actual renderer metrics. A bounded startup wait
that expires is unverified (exit 2). Subsequent asynchronous grid staging is reported alongside valid timings;
compilation, loading and missing active-renderer metrics remain unverified.
Run through the lock:  heavy.py --exclusive --loop middle --why "..." "<you>: game static probe" -- py -3 tools/harness/atlas_static.py
"""
import argparse
import json
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "tools"))
sys.path.insert(0, str(HERE.parents[1] / "tools/dev"))

import atlas_tools  # noqa: E402
import game  # noqa: E402


def profile_lines(path, offset):
    if not path.exists():
        return []
    with path.open("rb") as stream:
        stream.seek(offset)
        data = stream.read()
    return data[:data.rfind(b"\n") + 1].decode("utf-8", "replace").splitlines()


def active_sample(line):
    timing = re.search(r"atlasMs=(\d+(?:\.\d+)?)", line)
    reach = re.search(r'"reach":(\d+)', line)
    state = re.search(r"ready\{([^}]*)\}", line)
    state = state.group(1) if state else ""
    queued = re.fullmatch(r"pending: (?:grid (?:pending|waiting|gpuWaiting|coarseWaiting|pendingSections) \d+"
                          r"(?: \(the nearest \d+ blocks away\))?|level \d+ pending \d+|"
                          r"grid not settled since its last move)", state)
    return ((state == "ok" or queued is not None) and "unified=true" in line
            and re.search(r'"pipelinesBuilding"\s*:\s*0\s*[,}]', line) is not None
            and timing is not None and float(timing.group(1)) > 0
            and reach is not None and int(reach.group(1)) > 0
            and re.search(r"quality\{[^}]*\btier \d+\b", line) is not None)


def ready_sample(line):
    return "ready{ok}" in line and active_sample(line)


def wait_ready(path, offset, timeout):
    start = time.monotonic()
    while time.monotonic() - start < timeout:
        lines = profile_lines(path, offset)
        if lines and ready_sample(lines[-1]):
            return True
        pending = re.search(r"ready\{([^}]*)\}", lines[-1]) if lines else None
        print(f"TRACE progress static readiness {time.monotonic() - start:.0f}s: "
              f"{pending.group(1) if pending else 'no fresh renderer profile'}", flush=True)
        time.sleep(min(5, max(0, timeout - (time.monotonic() - start))))
    return False


def report_profile(lines, seconds, settle):
    invalid = [line for line in lines if not active_sample(line)]
    queued = [line for line in lines if "ready{pending:" in line and active_sample(line)]
    rows = []
    for line in lines:
        get = lambda pattern: (re.search(pattern, line) or [None, None])[1]
        ms, reach = get(r"atlasMs=([\d.]+)"), get(r'"reach":([\d.]+)')
        quality = get(r"(quality\{[^}]*\})")
        if ms is not None:
            rows.append((line[:8], float(ms), float(reach) if reach else -1, quality or ""))
    for t, ms, reach, quality in rows:
        print(f"{t} atlasMs {ms:6.2f} reach {reach:6.0f} {quality}")
    print(f"active renderer samples {len(lines) - len(invalid)}/{len(lines)}; asynchronous grid activity {len(queued)}")
    for line in queued[:3]:
        state = re.search(r"ready\{[^}]*\}", line)
        print(f"  {line[:18]}: {state.group(0)}")
    if not lines or invalid:
        print(f"UNVERIFIED: required active-renderer metrics absent in {len(invalid)}/{len(lines)} samples")
        for line in invalid[:3]:
            state = re.search(r"ready\{[^}]*\}", line)
            print(f"  {line[:18]}: {state.group(0) if state else 'no readiness'}; required metrics may be missing")
        return 2
    tail = rows[settle // 2:]
    if len(tail) < max(5, (seconds - settle) * 0.4):
        print("UNVERIFIED: insufficient post-settle profile samples")
        return 2
    ms = sorted(row[1] for row in tail)
    med = ms[len(ms) // 2]
    lo, hi = ms[0], ms[-1]
    reaches = [row[2] for row in tail if row[2] >= 0]
    tiers = [re.search(r"tier (\d)", row[3]).group(1) for row in tail if "tier" in row[3]]
    changes = sum(1 for a, b in zip(tiers, tiers[1:]) if a != b)
    flat = lo >= med * 0.85 and hi <= med * 1.15
    print(f"VERDICT atlasMs median {med:.2f} ms, range {lo:.2f}..{hi:.2f} ({'flat' if flat else 'NOT flat'} within +-15%); "
          f"reach {min(reaches, default=-1):.0f}..{max(reaches, default=-1):.0f}; tier changes {changes}")
    return 0 if flat and changes == 0 else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=180)
    ap.add_argument("--settle", type=int, default=30)
    ap.add_argument("--ready-timeout", type=int, default=60, help="bounded wait before the measurement; timeout is unverified")
    a = ap.parse_args()
    if a.seconds <= a.settle or a.settle < 0 or a.ready_timeout <= 0:
        ap.error("seconds must exceed nonnegative settle, and ready-timeout must be positive")
    groups = json.loads((HERE / "scenes/groups.json").read_text(encoding="utf-8"))

    class Runner:
        pass

    r = Runner()
    r.groups, r.game = groups, game
    print("TRACE begin static launch", flush=True)
    atlas_tools.ensure_spawn(r, game, {"group": "atlas"}, say=lambda m: print(m, flush=True))
    game.ensure("atlas", groups)
    print("TRACE end static launch", flush=True)
    prof = game.GAME / "atlas-profile.log"
    x, z, up = atlas_tools.START
    watching = False
    lines = []
    try:
        game.actions(["ui close", "perspective first", "hud off", "try time set 6000", "weather-off on",
                      f"visit {x} {z} 3 {atlas_tools.HEADING} 6 clear"], timeout=120)
        start = prof.stat().st_size if prof.exists() else 0
        if not wait_ready(prof, start, a.ready_timeout):
            print("UNVERIFIED: renderer never reached ready{ok}; startup/loading samples cannot prove quality stability")
            return 2
        start = prof.stat().st_size
        t0 = time.monotonic()
        watching = True
        print("TRACE begin static watch", flush=True)
        while time.monotonic() - t0 < a.seconds:
            time.sleep(10)
            print(f"TRACE progress static {time.monotonic() - t0:.0f}s", flush=True)
        lines = profile_lines(prof, start)  # snapshot before shutdown adds unrelated profile samples
    finally:
        if watching:
            print("TRACE end static watch", flush=True)
        game.close()
    return report_profile(lines, a.seconds, a.settle)


if __name__ == "__main__":
    sys.exit(main())
