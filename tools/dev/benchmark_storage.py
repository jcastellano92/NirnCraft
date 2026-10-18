"""Controlled, opt-in storage comparison. Never moves the repository or game saves.

Uses identical sources and offline dependencies; discards one warm-up per case.
Reports full compile/resource processing separately from no-change builds.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scratch', type=Path, required=True)
    parser.add_argument('--runs', type=int, default=3)
    args = parser.parse_args()
    scratch = args.scratch.resolve()
    if scratch == ROOT or scratch in ROOT.parents or ROOT in scratch.parents:
        raise ValueError('Scratch must be separate from the repository')
    scratch.mkdir(parents=True, exist_ok=True)
    home = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')).resolve()
    fast_home = scratch / 'gradle-home'
    # Only this project's Gradle/Forge dependencies; exclude unrelated Android/NeoForge caches.
    seed_start = time.perf_counter()
    for relative in ('wrapper', 'caches/8.8', 'caches/modules-2', 'caches/forge_gradle', 'caches/jars-9'):
        source = home / relative
        if source.exists():
            shutil.copytree(source, fast_home / relative, dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns('*.lock', '*.lck', 'gc.properties'))
    report = {'scratch': str(scratch), 'seedSeconds': time.perf_counter() - seed_start,
              'note': 'Warm filesystem caches; compile/resources, not worldgen or cold boot. Default paths unchanged.',
              'cases': []}
    output = ROOT / 'dist/weather-review/storage'
    output.mkdir(parents=True, exist_ok=True)
    # Freeze inputs on F: so live source editing cannot invalidate the comparison.
    project = Path(tempfile.mkdtemp(prefix='project-F-', dir=output))
    for name in ('src', 'gradle', 'gradlew.bat', 'gradlew', 'build.gradle', 'settings.gradle', 'gradle.properties'):
        source = ROOT / 'mod' / name
        if source.is_dir():
            shutil.copytree(source, project / name, dirs_exist_ok=True)
        elif source.exists():
            shutil.copy2(source, project / name)
    report['sourceSnapshot'] = str(project)
    cases = [('F-build_C-cache', output / 'build-F', home, project / '.gradle'),
             ('E-build_C-cache', scratch / 'build', home, scratch / 'project-cache-C'),
             ('E-build_E-cache', scratch / 'build-E-cache', fast_home, scratch / 'project-cache-E')]
    # Alternate measured cases to reduce drift from background work and heating.
    for iteration in range(args.runs + 1):
        for name, build, cache, project_cache in cases if iteration % 2 == 0 else reversed(cases):
            case = next((c for c in report['cases'] if c['name'] == name), None)
            if case is None:
                case = {'name': name, 'build': str(build), 'cache': str(cache), 'samples': []}
                report['cases'].append(case)
            for mode in ('full', 'unchanged'):
                command = [str(project / 'gradlew.bat'), 'compileJava', 'processResources',
                           '--offline', '--console=plain', '-Dorg.gradle.jvmargs=-Xmx2G',
                           '-PskycraftBuildDir=' + str(build), '--project-cache-dir', str(project_cache)]
                if mode == 'full':
                    command.append('--rerun-tasks')
                env = dict(os.environ, GRADLE_USER_HOME=str(cache))
                log = output / f'{name}-{iteration}-{mode}.log'
                start = time.perf_counter()
                with log.open('w') as stream:
                    result = subprocess.run(command, cwd=project, env=env, stdout=stream, stderr=subprocess.STDOUT)
                sample = {'iteration': iteration, 'warmup': iteration == 0, 'mode': mode,
                          'seconds': round(time.perf_counter() - start, 3), 'exitCode': result.returncode}
                case['samples'].append(sample)
                print(name, sample, flush=True)
                (output / 'benchmark.json').write_text(json.dumps(report, indent=2))
                if result.returncode:
                    raise RuntimeError('Build failed: ' + str(log))
    for case in report['cases']:
        case['medianSeconds'] = {mode: statistics.median(s['seconds'] for s in case['samples']
                                if not s['warmup'] and s['mode'] == mode) for mode in ('full', 'unchanged')}
    (output / 'benchmark.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
