"""Bounded stationary resource-reload trace of the grid's admission counters (not a gate; run via agent_job.py).

Launches/reuses the atlas client, waits for readiness, issues one `resources` reload with the camera held, and prints
the ready line plus the voxgrid counters (gpuWaiting, nearestMissing, cutoff, wanted*, rank*) every 2 seconds.
"""
import json
import os
import re
import shutil
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import game
import atlas_static
import atlas_tools

KEYS = ('gpuWaiting', 'waiting', 'nearestMissing', 'cutoff', 'reach', 'wantedAsked', 'wantedHeld', 'wantedReasked',
        'wantedPending', 'rankCut', 'rankWaitBest', 'rankAdmits', 'rankEvicts', 'resident', 'slotsTotal', 'settled')


def sample(line):
    ready = (re.search(r'ready\{[^}]*\}', line) or ['no ready'])[0]
    grid = {}
    for k in KEYS:
        m = re.search(r'"%s":([^,}]+)' % k, line)
        if m: grid[k] = m.group(1)
    return ready, grid

OUT = Path('E:/NirnCraft-scratch/codex-grid-evidence/trace-shots')


def shoot(name):
    """One capture at the held pose, copied beside the log so a reviewer (or agent) can open the real image."""
    OUT.mkdir(parents=True, exist_ok=True)
    x, z, _ = atlas_tools.START
    scene = {'id': 'grid-trace-%s-%d' % (name, int(time.time())), 'timeout': 40}
    shot, _rec, _, _ = game.capture(scene, 'vanilla', [f'visit {x} {z} 3 {atlas_tools.HEADING} 6 clear', 'hud on', 'wait 10'],
                                    lambda tag: [f'capture {tag}'])
    shutil.copy2(shot, OUT / (name + '.png'))
    print('TRACE captured', name, OUT / (name + '.png'), flush=True)


def main():
    watch = int(sys.argv[1]) if len(sys.argv) > 1 else 150
    groups = json.loads((HERE / 'scenes/groups.json').read_text(encoding='utf-8'))
    profile = game.GAME / 'atlas-profile.log'
    offset = profile.stat().st_size if profile.exists() else 0
    game.assert_prepared()
    os.environ['NIRNCRAFT_HARNESS_NO_BUILD'] = '1'
    game.ensure('atlas', groups)
    x, z, _ = atlas_tools.START
    game.actions(['ui close', 'perspective first', 'hud on', 'try time set 6000', 'weather-off on',
                  f'visit {x} {z} 3 {atlas_tools.HEADING} 6 clear'], timeout=60)
    end = time.monotonic() + 200
    while time.monotonic() < end:
        lines = atlas_static.profile_lines(profile, offset)
        ready, grid = sample(lines[-1]) if lines else ('no profile', {})
        print('TRACE launch', ready, grid, flush=True)
        if ready == 'ready{ok}': break
        time.sleep(4)
    shoot('before')
    offset = profile.stat().st_size
    print('TRACE reload start', flush=True)
    game.actions(['resources'], timeout=60)
    t0 = time.monotonic()
    while time.monotonic() < t0 + watch:
        lines = atlas_static.profile_lines(profile, offset)
        ready, grid = sample(lines[-1]) if lines else ('no profile', {})
        print('TRACE reload %3ds' % (time.monotonic() - t0), ready, grid, flush=True)
        time.sleep(2)
    shoot('after')
    game.close()


if __name__ == '__main__':
    main()

