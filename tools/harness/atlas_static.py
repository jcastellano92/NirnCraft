"""A static camera watched for a few minutes (the MIDDLE loop for the quality controller and level 0's reach).

    py -3 tools/harness/atlas_static.py [--seconds 180] [--settle 30]

The client starts at the route's spawn (atlas_tools.ensure_spawn), the camera is put there and left alone; the passive
profile line (atlas-profile.log, every 2 s) is read back: per sample atlasMs, level 0's reach, the controller's block
(quality{tier, budget, gpu, atlas, scale, lod}). Verdict after `settle` seconds: atlasMs within +-15 % of its median, the
reach's range, the tier changes. A static camera must not cycle (10-26: 2.5 -> 30 ms every 30 s, reach 126 <-> 1208).
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=180)
    ap.add_argument("--settle", type=int, default=30)
    a = ap.parse_args()
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
    game.actions(["ui close", "perspective first", "hud off", "try time set 6000", "weather-off on",
                  f"visit {x} {z} 3 {atlas_tools.HEADING} 6 clear"], timeout=120)
    start = prof.stat().st_size if prof.exists() else 0
    t0 = time.monotonic()
    print("TRACE begin static watch", flush=True)
    try:
        while time.monotonic() - t0 < a.seconds:
            time.sleep(10)
            print(f"TRACE progress static {time.monotonic() - t0:.0f}s", flush=True)
    finally:
        print("TRACE end static watch", flush=True)
        game.close()
    with open(prof, "rb") as f:
        f.seek(start)
        lines = f.read().decode("utf-8", "replace").splitlines()
    rows = []
    for l in lines:
        g = lambda p: (re.search(p, l) or [None, None])[1]
        ms, reach = g(r"atlasMs=([\d.]+)"), g(r'"reach":([\d.]+)')
        q = g(r"(quality\{[^}]*\})")
        if ms is None:
            continue
        rows.append((l[:8], float(ms), float(reach) if reach else -1, q or ""))
    for t, ms, reach, q in rows:
        print(f"{t} atlasMs {ms:6.2f} reach {reach:6.0f} {q}")
    skip = a.settle // 2
    tail = rows[skip:]
    if not tail:
        print("FAIL: no profile lines")
        return 1
    ms = sorted(r[1] for r in tail)
    med = ms[len(ms) // 2]
    lo, hi = ms[0], ms[-1]
    reaches = [r[2] for r in tail if r[2] >= 0]
    tiers = [re.search(r"tier (\d)", r[3]).group(1) for r in tail if "tier" in r[3]]
    changes = sum(1 for i in range(1, len(tiers)) if tiers[i] != tiers[i - 1])
    flat = lo >= med * 0.85 and hi <= med * 1.15
    print(f"VERDICT atlasMs median {med:.2f} ms, range {lo:.2f}..{hi:.2f} ({'flat' if flat else 'NOT flat'} within +-15%); "
          f"reach {min(reaches, default=-1):.0f}..{max(reaches, default=-1):.0f}; tier changes {changes}")
    return 0 if flat and changes == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
