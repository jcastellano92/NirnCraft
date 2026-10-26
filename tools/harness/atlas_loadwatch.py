"""A launch watched until atlas is ready (the MIDDLE loop for the loading path: no route, no captures; about 2 to 5 minutes).

    py -3 tools/harness/atlas_loadwatch.py [--cold] [--limit 300]

The client restarts at the route's fixed spawn (atlas_tools.ensure_spawn); --cold wipes atlas's cache first. The profile line
is read every few seconds (the near window's chunks up of the window, the bake's tiles) and printed as TRACE progress; the
client is closed when atlas-load.json says ready (or gave up), or at --limit seconds. Prints the receipt's numbers.
Run through the lock:  heavy.py --exclusive --loop middle --why "..." "<you>: perf loadwatch" -- py -3 tools/harness/atlas_loadwatch.py
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

import atlas_tools  # noqa: E402
import game  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cold", action="store_true")
    ap.add_argument("--limit", type=int, default=300)
    a = ap.parse_args()
    groups = json.loads((HERE / "scenes/groups.json").read_text(encoding="utf-8"))

    class Runner:
        pass

    r = Runner()
    r.groups, r.game = groups, game
    atlas_tools.ensure_spawn(r, game, {"group": "atlas"}, say=lambda m: print(m, flush=True))
    if a.cold:
        print(f"  atlas cache wiped ({atlas_tools.wipe_cache(game)} files)", flush=True)
    load = game.GAME / "atlas-load.json"
    load.unlink(missing_ok=True)
    prof = game.GAME / "atlas-profile.log"
    start = prof.stat().st_size if prof.exists() else 0
    t0 = time.monotonic()
    print("TRACE begin loadwatch", flush=True)
    launched = None
    failure = None
    try:
        # (the launch call returns once the loading screen lets the client's heartbeat through: it waits for the load)
        import threading
        th = threading.Thread(target=lambda: game.ensure("atlas", groups), daemon=True)
        th.start()
        last = 0
        spawn_seen, spawn_at = None, time.monotonic()
        while time.monotonic() - t0 < a.limit and not load.exists() and not failure:
            time.sleep(2.0)
            if time.monotonic() - last < 10:
                continue
            last = time.monotonic()
            try:
                with open(prof, "rb") as f:
                    f.seek(max(start, prof.stat().st_size - 12000))
                    line = f.read().decode("utf-8", "replace").splitlines()[-1]
            except (OSError, IndexError):
                continue
            up = re.search(r" up=(\d+)/(\d+) sections", line)
            bk = re.search(r"levels 3-6 (\d+)/(\d+)", line)
            st = re.search(r"store\{[^}]*\}", line)
            rd = re.search(r"ready\{([^}]{0,60})", line)
            # invariants, named when broken (a stall like "289 of 441 for good, the cache served 1893" names itself here):
            # a ratio never over 100%, and while the spawn area is pending its count moves within 45 s
            for m in re.finditer(r"(\d+) of (\d+) (?:chunks|tiles)", rd.group(1) if rd else ""):
                if int(m.group(1)) > int(m.group(2)):
                    failure = f"a progress ratio over 100%: {m.group(0)}"
            sp = re.search(r"spawn area (\d+) of (\d+) chunks", rd.group(1) if rd else "")
            cs = re.search(r"cached chunks (\d+)", line)
            ch = re.search(r"clientGen\{[^}]*? chunks=(\d+)", line)
            if sp:
                if spawn_seen != int(sp.group(1)):
                    spawn_seen, spawn_at = int(sp.group(1)), time.monotonic()
                elif time.monotonic() - spawn_at > 45:
                    failure = (f"the spawn area stuck at {sp.group(1)} of {sp.group(2)} chunks for 45 s (the cache served "
                               f"{cs.group(1) if cs else '?'}, the worker asked for {ch.group(1) if ch else '?'}): chunks served or made are not up in the grid")
            else:
                spawn_seen, spawn_at = None, time.monotonic()
            print(f"TRACE progress loadwatch {time.monotonic() - t0:.0f}s chunks up {up.group(1) if up else '?'} bake {bk.group(1) if bk else '?'}/"
                  f"{bk.group(2) if bk else '?'} {st.group(0) if st else ''} {rd.group(1) if rd else ''}", flush=True)
        launched = time.monotonic() - t0
    finally:
        game.close()
    if failure:
        print(f"FAIL: {failure}")
        return 1
    if not load.exists():
        print(f"FAIL: atlas was not ready after {launched:.0f} s")
        return 1
    d = json.loads(load.read_text(encoding="utf-8"))
    print(f"TRACE done loadwatch {launched:.0f}s")
    print({k: d.get(k) for k in ("ready", "toReadyS", "toRendererS", "toGeneratorS", "toSpawnAreaS", "toHorizonS")})
    print("frames", d.get("frames"))
    sub = atlas_tools.parse_subs(d.get("subs", ""))
    print(f"submissions {sub['submissions']}, over 8 ms {sub['over8']}, longest {sub['max']} ms")
    for row in sub["rows"][:8]:
        print(f"  {row['phase']:30s} n {row['n']:6d} mean {row['mean']:6.2f} max {row['max']:6.1f} >8 {row['over8']}")
    print("bake:", d.get("bake"))
    return 0 if d.get("ready") else 1


if __name__ == "__main__":
    sys.exit(main())
