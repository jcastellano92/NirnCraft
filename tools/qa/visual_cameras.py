"""Plan -> loaded-world scout -> visual review -> reusable, generation-bound camera.

Use `plan` on the real air-survey before scouting a dungeon. `approve` requires an
actual screenshot receipt and a plan/route reference; `run` refuses stale presets.
"""
import argparse
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('live', ROOT / 'tools/dev/live_world.py')
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)
PRESETS = ROOT / 'tools/qa/visual-cameras.json'
CAPTURES = live.GAME / 'build/autotest-screenshots'


def same_generation(saved, current):
    keys = ('seed', 'dimension', 'generationInputs')
    return (all(key in saved and key in current and saved[key] is not None for key in keys)
            and bool(saved['dimension']) and bool(saved['generationInputs'])
            and all(saved[key] == current[key] for key in keys))


def validate_receipt(receipt):
    if not receipt.get('cameraClear') or receipt.get('readinessPassed') is not True:
        raise ValueError('A clear camera and successful readiness proof at that pose are required')
    if receipt.get('nightVision') is not False:
        raise ValueError('Clear Night Vision and capture actual scene lighting before approving')
    if not same_generation(receipt, receipt):
        raise ValueError('Missing generation identity')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    plan = sub.add_parser('plan')
    plan.add_argument('survey', type=Path)
    plan.add_argument('place')
    scout = sub.add_parser('scout')
    scout.add_argument('coordinates', type=float, nargs=6)
    approve = sub.add_parser('approve')
    approve.add_argument('name')
    approve.add_argument('capture')
    approve.add_argument('--plan', required=True, help='Actual plan/survey and room/route reference')
    approve.add_argument('--purpose', required=True)
    approve.add_argument('--region', required=True, help='Actual region ID from regions.json')
    approve.add_argument('--systems', required=True, help='Comma-separated covered systems; composition must show each')
    run = sub.add_parser('run')
    run.add_argument('name')
    run.add_argument('--tag')
    args = parser.parse_args()
    if args.action == 'plan':
        rows = [r for r in json.loads(args.survey.read_text()) if r['place'] == args.place]
        result = []
        for row in rows:
            if row['kind'] not in ('ROOM', 'CORRIDOR', 'TUNNEL'):
                continue
            x0, y0, z0, x1, y1, z1 = row['box']
            x, z = (x0+x1)/2, (z0+z1)/2
            along_x = x1-x0 > z1-z0
            dx, dz = ((x1-x0)*0.22, 0) if along_x else (0, (z1-z0)*0.22)
            result.append({'place': args.place, 'kind': row['kind'], 'room': row.get('room'),
                           'routeBox': row['box'], 'features': row.get('features', []),
                           'position': [x-dx, row['floor']+1.2, z-dz],
                           'lookAt': [x+dx, row['floor']+2.5, z+dz], 'status': 'unverified plan candidate'})
        print(json.dumps(result, indent=2))
    elif args.action == 'scout':
        live.live_actions(['camera-plan ' + ' '.join(map(str, args.coordinates))])
        print((live.GAME / 'world-preview/camera-candidates.json').read_text())
    elif args.action == 'approve':
        receipt = live.read_json(CAPTURES / (args.capture + '.json'))
        validate_receipt(receipt)
        if not (CAPTURES / (args.capture + '.png')).is_file():
            raise ValueError('A real clear-camera screenshot and receipt are required')
        presets = live.read_json(PRESETS) or {'version': 1, 'cameras': {}}
        presets['cameras'][args.name] = {**receipt, 'planReference': args.plan,
                                      'purpose': args.purpose, 'region': args.region,
                                      'systems': args.systems.split(','),
                                      'review': 'visually reviewed', 'evidence': args.capture}
        live.atomic(PRESETS, (json.dumps(presets, indent=2) + '\n').encode())
        print('Saved', args.name)
    else:
        saved = live.read_json(PRESETS)['cameras'][args.name]
        state = live.require_ready()
        # Refresh a cheap identity receipt; never fly to a stale coordinate to find out.
        live.live_actions(['identity'])
        current = live.read_json(live.GAME / 'world-preview/camera-identity.json')
        if not same_generation(saved, current):
            raise ValueError('STALE camera: seed, dimension or generation changed. Revisit its plan/route, scout locally and visually reapprove. '
                             'Ensure this world area is regenerated for the current inputs first.')
        if saved.get('review') != 'visually reviewed':
            raise ValueError('Camera has not passed visual review')
        pose = saved['position'] + saved['lookAt']
        live.live_actions(['camera ' + ' '.join(map(str, pose)), 'settle 1800',
                           'capture ' + (args.tag or args.name)])


if __name__ == '__main__':
    main()
