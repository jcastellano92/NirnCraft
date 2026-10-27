"""Fixed-pose paired capture for renderer parity (not a gate; run via agent_job.py, one backend per run).

    py -3 tools/harness/parity_capture.py <opengl|vulkan> <outdir> [--settle SECONDS]

Sets preferredGraphicsBackend in options.txt while the client is closed (restored afterwards), launches the atlas
client at the route's fixed spawn, waits for readiness plus a settle time, then captures the same poses with the
time of day and weather fixed. Images and the profile line at each capture are saved beside the png files.
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

POSES = [('p0', 0), ('p1', 90), ('p2', 180), ('p3', 270)]  # heading offsets from the route's heading, degrees


def set_backend(mode):
    options = game.GAME / 'options.txt'
    raw = options.read_bytes()
    lines = raw.split(b'\n')
    hits = [i for i, l in enumerate(lines) if l.startswith(b'preferredGraphicsBackend:')]
    if len(hits) != 1: raise RuntimeError('options.txt has no unique preferredGraphicsBackend line')
    ending = b'\r' if lines[hits[0]].endswith(b'\r') else b''
    lines[hits[0]] = b'preferredGraphicsBackend:"%s"' % mode.encode() + ending
    game.lw.atomic(options, b'\n'.join(lines))
    return raw


def main():
    mode, out = sys.argv[1], Path(sys.argv[2])
    settle = int(sys.argv[sys.argv.index('--settle') + 1]) if '--settle' in sys.argv else 25
    if mode not in ('opengl', 'vulkan'): raise SystemExit('mode must be opengl or vulkan')
    out.mkdir(parents=True, exist_ok=True)
    groups = json.loads((HERE / 'scenes/groups.json').read_text(encoding='utf-8'))
    profile = game.GAME / 'atlas-profile.log'
    latest = game.GAME / 'logs/latest.log'
    offset = profile.stat().st_size if profile.exists() else 0
    os.environ['NIRNCRAFT_HARNESS_NO_BUILD'] = '1'
    if game.running(): game.close()
    original = set_backend(mode)
    summary = {'mode': mode, 'shots': []}
    try:
        game.ensure('atlas', groups)
        x, z, _ = atlas_tools.START
        game.actions(['ui close', 'perspective first', 'hud on', 'try time set 6000', 'weather-off on',
                      f'visit {x} {z} 3 {atlas_tools.HEADING} 6 clear'], timeout=60)
        end = time.monotonic() + 150
        line = ''
        while time.monotonic() < end:
            lines = atlas_static.profile_lines(profile, offset)
            line = lines[-1] if lines else ''
            if atlas_static.ready_sample(line): break
            time.sleep(3)
        summary['ready'] = (re.search(r'ready\{[^}]*\}', line) or ['none'])[0]
        time.sleep(settle)
        text = latest.read_text(encoding='utf-8', errors='replace') if latest.exists() else ''
        summary['backend_log'] = (re.findall(r'Using graphics backend (\w+)', text) or ['?'])[-1]
        for name, turn in POSES:
            heading = (atlas_tools.HEADING + turn) % 360
            scene = {'id': 'parity-%s-%s-%d' % (mode, name, int(time.time())), 'timeout': 45}
            shot, rec, _, _ = game.capture(scene, 'vanilla', [f'visit {x} {z} 3 {heading} 6 clear', 'try time set 6000', 'hud off', 'wait 12'],
                                           lambda tag: [f'capture {tag}'])
            shutil.copy2(shot, out / f'{mode}-{name}.png')
            lines = atlas_static.profile_lines(profile, offset)
            summary['shots'].append({'pose': name, 'heading': heading, 'file': f'{mode}-{name}.png',
                                     'receipt': rec, 'profile': lines[-1][:4000] if lines else ''})
            print('TRACE captured', mode, name, flush=True)
    finally:
        (out / f'{mode}-summary.json').write_text(json.dumps(summary, indent=2, default=str), encoding='utf-8')
        try:
            if game.running(): game.close()
        finally:
            game.lw.atomic(game.GAME / 'options.txt', original)


if __name__ == '__main__':
    main()
