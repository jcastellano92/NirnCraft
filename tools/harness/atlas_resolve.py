"""Time to resolve the near field after a teleport and in fast flight (the MIDDLE loop for streaming; about 3 minutes).

    py -3 tools/harness/atlas_resolve.py [--jump DX DZ] [--speed 21.6] [--fly 30] [--still 15]

The client starts at the route's spawn (atlas_tools.ensure_spawn) and settles there; then one route (AtlasRoute) whose
"arrive" phase starts with a jump of (DX, DZ) blocks (a teleport, the route's own): `still` seconds where it lands, `fly` seconds east over the ground at `speed` blocks a
second (21.6: creative sprint flight), 10 s still. Per second it prints level 0's state from the route's own readings:
l0Near6 (level-0 columns within 6 chunks / 96 blocks holding nothing), nearestMissing (the nearest section level 0
knows of, within its reach, and does not hold; 0 = none), sections built, the generator's chunks, what waits.
Verdict lines: seconds from the teleport until l0Near6 is 0 (the ~64-96 block field) and until nearestMissing is 0
(the whole level-0 reach); in flight, the seconds with l0Near6 > 0 and its worst.
Run through the lock:  heavy.py --exclusive --loop middle --why "..." "<you>: live resolve" -- py -3 tools/harness/atlas_resolve.py
"""
import argparse
import json
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
    ap.add_argument("--jump", nargs=2, type=int, default=[2400, 700], help="blocks east, south of the route's spawn")
    ap.add_argument("--speed", type=float, default=21.6)
    ap.add_argument("--fly", type=int, default=30)
    ap.add_argument("--still", type=int, default=15)
    a = ap.parse_args()
    groups = json.loads((HERE / "scenes/groups.json").read_text(encoding="utf-8"))

    class Runner:
        pass

    r = Runner()
    r.groups, r.game = groups, game
    print("TRACE begin resolve launch", flush=True)
    atlas_tools.ensure_spawn(r, game, {"group": "atlas"}, say=lambda m: print(m, flush=True))
    game.ensure("atlas", groups)
    print("TRACE end resolve launch", flush=True)
    out_dir = game.GAME / "build/autotest-route"
    out_dir.mkdir(parents=True, exist_ok=True)
    tag = "resolve"
    report = out_dir / f"{tag}.json"
    report.unlink(missing_ok=True)
    spec = {"tag": tag, "heading": atlas_tools.HEADING, "captures": [], "uncap": False, "phases": [
        {"name": "before", "ticks": 40, "speed": 0.0, "mode": "hold", "pitch": 6},
        {"name": "arrive", "ticks": a.still * 20, "speed": 0.0, "mode": "ground", "pitch": 6, "jump": a.jump},
        {"name": "fly", "ticks": a.fly * 20, "speed": a.speed, "mode": "ground", "pitch": 6},
        {"name": "still", "ticks": 200, "speed": 0.0, "mode": "hold", "pitch": 6},
    ]}
    spec_file = out_dir / f"spec-{tag}.json"
    spec_file.write_text(json.dumps(spec), encoding="utf-8")
    x, z, up = atlas_tools.START
    # (the route starts ready, at the warm spawn; its "arrive" phase begins with the jump: a teleport the route measures)
    lines = ["ui close", "perspective first", "hud off", "try time set 6000", "weather-off on",
             f"visit {x} {z} 3 {atlas_tools.HEADING} 6 clear", "settle-atlas 3600 100", f"route {spec_file.as_posix()}"]
    print("TRACE begin resolve route", flush=True)
    t0 = time.monotonic()
    try:
        game.actions(lines, timeout=600)
    finally:
        print(f"TRACE end resolve route {time.monotonic() - t0:.0f}s", flush=True)
        game.close()
    if not report.exists():
        print("FAIL: the route wrote no report")
        return 1
    data = json.loads(report.read_text(encoding="utf-8"))
    st = out_dir / "settle.json"
    if st.exists():
        sj = json.loads(st.read_text(encoding="utf-8"))
        print("settle at the spawn:", {k: sj.get(k) for k in ("ok", "ms", "timeToNoHolesMs", "why")})
    secs = data.get("seconds", [])
    keys = ["l0Near6", "nearestMissing", "built", "genChunks", "gpuWaiting", "pending", "resident", "reach"]
    print("sec  phase   " + " ".join(f"{k:>14s}" for k in keys) + "  frame p99")
    rows = []
    for row in secs:
        at = row.get("atlas") or {}
        rows.append((row.get("sec"), row.get("phase", ""), at))
        print(f"{row.get('sec'):3d}  {str(row.get('phase', ''))[:6]:6s}  " + " ".join(f"{at.get(k, '-')!s:>14s}" for k in keys)
              + f"  {row.get('frame', {}).get('p99', '-')}")

    def first_zero(key, phase):
        for s, ph, at in rows:
            if ph == phase and at.get(key) == 0:
                return s
        return None
    t_near = first_zero("l0Near6", "arrive")
    t_all = first_zero("nearestMissing", "arrive")
    fly = [at for s, ph, at in rows if ph == "fly"]
    bad = [at.get("l0Near6", 0) for at in fly if (at.get("l0Near6") or 0) > 0]
    print(f"VERDICT after the teleport: l0Near6 0 at {t_near} s, nearestMissing 0 at {t_all} s (targets 2 s, 5 s)")
    print(f"VERDICT flight at {a.speed} b/s: {len(bad)} of {len(fly)} s with level-0 columns empty within 96 blocks, worst {max(bad, default=0)}")
    print("subs", data.get("subs", "")[:600])
    return 0


if __name__ == "__main__":
    sys.exit(main())
