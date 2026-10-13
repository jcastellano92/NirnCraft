#!/usr/bin/env python3
"""Floating-landmark census on the dev server (docs/AGENT-RULES.md fast loop).

Regenerates an area in tiles with the current code, then runs the server census
`/skycraft-landmarks groundcheck <x> <z> <half>` (com.skycraft.landmarks.GroundCheck) tile by tile: every landmark
site, minor site and scene prop is scanned for parts of solid blocks with no way down to the terrain (a flood fill
over the finished chunks), and a control sample of plain terrain gives the natural rate. Prints the summed table and
the worst sites; exit code 1 when more than --max-share of the built sites float.

  py -3 -I tools/qa/landmark_ground_check.py --center 0 0 --half 1500            # regen 3x3 km, census, grounding ON
  py -3 -I tools/qa/landmark_ground_check.py --center 0 0 --half 1500 --legacy   # the same with the old placement
  py -3 -I tools/qa/landmark_ground_check.py --center 0 0 --half 1500 --no-regen # census of what is there

--legacy switches `/skycraft-landmarks grounding false` first (the five-point settle of before the fix, no foundations,
no support pass) and back on afterwards, so one server run gives the before/after pair. A dev server restart (new
code) resets the switch to ON. The census is per 500-block tile: one command that loads the 3x3 chunks of every site
of 9 km2 at once ran a 4 GB server out of heap (chunks cannot unload inside one tick).
One dev server per machine: this uses tools/dev/devserver.ps1; set SKYCRAFT_DEV_FORCE=1 to pass -Force when
another worktree's server is up and there is RAM for two.
"""
import argparse
import json
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEV = os.path.join(ROOT, "tools", "dev", "devserver.ps1")
OUT_JSON = os.path.join(ROOT, "mod", "run", "devworld", "skycraft-landmarks", "groundcheck.json")
FORCE = os.environ.get("SKYCRAFT_DEV_FORCE") == "1"
TILE = 500  # blocks; 32x32 chunks: a 63x63 tile ran a 4 GB dev server out of heap


def dev(cmds, timeout=3600):
    """Runs lines through the dev server inbox; returns (exit code, output)."""
    args = ["powershell", "-NoProfile", "-File", DEV, "run", "|".join(cmds), "-Wait", "-Timeout", str(timeout)]
    if FORCE:
        args.append("-Force")
    # output goes to a file, not a pipe: the call may boot the server, which would inherit a pipe and keep it open
    log = os.path.join(ROOT, "mod", "run", "gc_dev_call.txt")
    with open(log, "w", encoding="utf-8") as f:
        p = subprocess.run(args, stdout=f, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    with open(log, encoding="utf-8", errors="replace") as f:
        return p.returncode, f.read()


def restart():
    args = ["powershell", "-NoProfile", "-File", DEV, "restart"] + (["-Force"] if FORCE else [])
    with open(os.path.join(ROOT, "mod", "run", "gc_dev_call.txt"), "w", encoding="utf-8") as f:
        subprocess.run(args, stdout=f, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)


def tiles(x0, z0, x1, z1):
    x = x0
    while x < x1:
        z = z0
        while z < z1:
            yield (x, z, min(x + TILE - 1, x1), min(z + TILE - 1, z1))
            z += TILE
        x += TILE


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--center", nargs=2, type=int, default=[0, 0])
    ap.add_argument("--half", type=int, default=1500)
    ap.add_argument("--no-regen", action="store_true")
    ap.add_argument("--restart-every", type=int, default=12, help="restart the dev server every N regen tiles (a 4 GB heap runs out after ~40)")
    ap.add_argument("--legacy", action="store_true", help="old placement (grounding off) for the regen and the run")
    ap.add_argument("--max-share", type=float, default=0.02, help="allowed share of floating built sites")
    ap.add_argument("--json", default="", help="write the summed census JSON here")
    a = ap.parse_args()
    cx, cz, half = a.center[0], a.center[1], a.half
    area = list(tiles(cx - half, cz - half, cx + half, cz + half))

    rc, out = dev(["skycraft-landmarks grounding %s" % ("false" if a.legacy else "true")])
    if rc != 0:
        print(out[-600:])
        return 2
    if not a.no_regen:
        for i, (x0, z0, x1, z1) in enumerate(area):
            if i and a.restart_every and i % a.restart_every == 0:
                restart()
                dev(["skycraft-landmarks grounding %s" % ("false" if a.legacy else "true")])
            rc, out = dev(["skycraft-dev regen %d %d %d %d" % (x0, z0, x1, z1), "save-all flush"])
            print("regen %d/%d (%d,%d)-(%d,%d): %s" % (i + 1, len(area), x0, z0, x1, z1, "ok" if rc == 0 else "FAILED rc=%d" % rc), flush=True)
            if rc != 0:
                print(out[-600:])
                return 2
    cats, bad, counters = {}, [], ""
    for i, (x0, z0, x1, z1) in enumerate(area):
        tcx, tcz, th = (x0 + x1) // 2, (z0 + z1) // 2, (x1 - x0 + 1) // 2
        rc, out = dev(["skycraft-landmarks groundcheck %d %d %d" % (tcx, tcz, th)])
        if rc != 0:
            print("census tile %d failed rc=%d\n%s" % (i + 1, rc, out[-600:]))
            return 2
        try:
            with open(OUT_JSON, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError) as e:
            print("could not read the census JSON: %s" % e)
            return 2
        for cat, v in data["categories"].items():
            t = cats.setdefault(cat, {"sites": 0, "built": 0, "floating": 0, "stray": 0, "blocks": 0, "worstGap": 0})
            for k in ("sites", "built", "floating", "stray", "blocks"):
                t[k] += v[k]
            t["worstGap"] = max(t["worstGap"], v["worstGap"])
        bad += data["floatingSites"]
        counters = data.get("counters", "")
        print("census %d/%d done" % (i + 1, len(area)), flush=True)
    if a.legacy:
        dev(["skycraft-landmarks grounding true"])

    print("census %d,%d +-%d, grounding %s" % (cx, cz, half, "OFF (legacy)" if a.legacy else "ON"))
    print("  %-18s %6s %6s %6s %7s %6s %8s" % ("category", "sites", "built", "float", "share", "stray", "blocks"))
    floating = built = 0
    for cat in sorted(cats):
        t = cats[cat]
        share = 100.0 * t["floating"] / t["built"] if t["built"] else 0.0
        print("  %-18s %6d %6d %6d %6.1f%% %6d %8d" % (cat, t["sites"], t["built"], t["floating"], share, t["stray"], t["blocks"]))
        if cat != "control":
            floating += t["floating"]
            built += t["built"]
    worst = sorted(bad, key=lambda s: -s["blocks"])[:15]
    if worst:
        print("worst sites:")
        for s in worst:
            print("  %-16s %-26s at %6d %6d  parts %d blocks %d gap %s" % (s["category"], s["kind"], s["x"], s["z"], s["parts"], s["blocks"], s["gap"]))
    by_kind = {}
    for s in bad:
        if s["category"] != "control":
            by_kind[s["kind"]] = by_kind.get(s["kind"], 0) + 1
    if by_kind:
        print("floating by kind: " + ", ".join("%s=%d" % kv for kv in sorted(by_kind.items(), key=lambda kv: -kv[1])[:20]))
    if counters:
        print("server counters: " + counters)
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump({"categories": cats, "floatingSites": bad, "grounding": not a.legacy}, f, indent=1)
    share = floating / built if built else 0.0
    print("floating built sites: %d of %d (%.1f%%), limit %.1f%%" % (floating, built, 100 * share, 100 * a.max_share))
    return 0 if share <= a.max_share else 1


if __name__ == "__main__":
    sys.exit(main())
