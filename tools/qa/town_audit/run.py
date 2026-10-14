"""Regenerates docs/world/town_audit.json (the TO0 town audit, acceptance numbers for TO1-TO6).

usage: py -3 -I tools/qa/town_audit/run.py [--world <world_dir>] [--no-world]

Reads the baked street plan (mod/src/main/resources/skycraft/baked/streetplan.bin) and the places; no server is booted.
With a saved world (default mod/run/world, or --world / SKYCRAFT_WORLD) it also flood-fills Whiterun and Helgen over the
generated blocks (world_truth).  Intermediate files go to tools/qa/out/town_audit/ (gitignored).
Steps: plan_audit.py (walk raster, key buildings, props, scatter, repeats) -> budget.py (free lots) -> gates.py (gate and
road ends) -> [bfs_truth.py + truth_summary.py per settlement in the world] -> make_json.py (rows, npc jobs) -> final.py.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.abspath(os.path.join(HERE, '..', 'out', 'town_audit'))
os.makedirs(OUT, exist_ok=True)


def step(script, *args):
    print('==', script, *args, flush=True)
    r = subprocess.run([sys.executable, '-I', os.path.join(HERE, script), *args], cwd=OUT)
    if r.returncode != 0:
        sys.exit(r.returncode)


def main():
    argv = sys.argv[1:]
    world = None
    if '--world' in argv:
        world = argv[argv.index('--world') + 1]
        os.environ['SKYCRAFT_WORLD'] = world
    step('plan_audit.py')
    step('budget.py')
    step('gates.py')
    if '--no-world' not in argv:
        w = world or os.environ.get('SKYCRAFT_WORLD') or os.path.join(HERE, '..', '..', '..', 'mod', 'run', 'world')
        if os.path.isdir(os.path.join(w, 'region')):
            step('bfs_truth.py', 'whiterun', '175')
            step('bfs_truth.py', 'helgen', '90')
            step('truth_summary.py')
    step('make_json.py')
    step('final.py')


if __name__ == '__main__':
    main()
