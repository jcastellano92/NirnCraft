"""The far-path probe: the MIDDLE loop of the atlas work (about a minute and a half, any cache), between the offline benches
(native/tools/gen_bake_tool, gen_cache_bench: seconds) and the outer loop (the route and the relaunch, at a finished step).

    py -3 tools/harness/atlas_probe.py [--tiles K] [--max-ms 8]

Launches the autotest client in the `atlas-probe` group (the atlas group without the loading screen, the near window idle,
the idle verifier off), which makes the K tiles nearest the spawn of each of levels 3 to 6 go through the whole far path
(the four passes, the tree stage, the chunks) unpaced, as the loading bake does, and writes `atlas-probe.json` in the game
folder (AtlasCoarseBake.probe): each tile's wall time and the generator's submissions by phase. Nothing is written to the
cache. The client restarts at the route's fixed spawn (atlas_tools.ensure_spawn) and is closed at the end. Run it through
the lock like every game run:  py -3 E:/NirnCraft-scratch/locks/heavy.py --exclusive "<you>: perf probe" -- py -3 tools/harness/atlas_probe.py
Exit status 1 when a generator submission ran over --max-ms.
"""
import argparse
import json
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "tools"))

import atlas_tools  # noqa: E402
import game  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tiles", type=int, default=6, help="tiles per level (the nearest the spawn, a spiral)")
    ap.add_argument("--max-ms", type=float, default=atlas_tools.BAR["genSubmissionMs"])
    ap.add_argument("--timeout", type=int, default=420)
    a = ap.parse_args()
    groups = json.loads((HERE / "scenes/groups.json").read_text(encoding="utf-8"))
    groups["atlas-probe"]["jvm"]["nirncraft.atlas.probe.far"] = str(a.tiles)

    class Runner:  # (what ensure_spawn reads of the harness' runner)
        pass

    r = Runner()
    r.groups = groups
    r.game = game
    atlas_tools.ensure_spawn(r, game, {"group": "atlas-probe"}, say=lambda m: print(m, flush=True))
    out = game.GAME / "atlas-probe.json"
    out.unlink(missing_ok=True)
    t0 = time.monotonic()
    print("TRACE begin probe", flush=True)
    try:
        game.ensure("atlas-probe", groups)
        launched = time.monotonic() - t0
        while not out.exists() and time.monotonic() - t0 < a.timeout:
            time.sleep(1.0)
            if not game.running():
                break
        if not out.exists():
            print("the probe wrote nothing (see logs/latest.log)")
            return 2
        data = json.loads(out.read_text(encoding="utf-8"))
    finally:
        game.close()
    sub = atlas_tools.parse_subs(data["subs"])
    levels = {}
    for lvl, tx, tz, ms, res in data["tiles"]:
        levels.setdefault(lvl, []).append(ms)
    print(f"TRACE done probe {time.monotonic() - t0:.0f}s (launch {launched:.0f} s, far path {data['totalS']:.1f} s)")
    for lvl in sorted(levels):
        v = levels[lvl]
        print(f"  level {lvl}: {len(v)} tiles, mean {sum(v) / len(v):.0f} ms, max {max(v):.0f} ms, verify results "
              f"{[x[4] for x in data['tiles'] if x[0] == lvl]}")
    print(f"  {sub['submissions']} submissions, {sub['over8']} over 8 ms; longest {sub['max']} ms")
    for row in sub["rows"][:10]:
        print(f"    {row['phase']:34s} n {row['n']:5d} mean {row['mean']:6.2f} max {row['max']:6.1f} ms  >4 {row['over4']} >8 {row['over8']} >16 {row['over16']}")
    if sub["max"] > a.max_ms:
        print(f"FAIL: a generator submission of {sub['max']} ms > {a.max_ms:g}")
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
