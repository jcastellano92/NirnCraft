"""Prepare native MDG server launch files without starting Minecraft."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def java_arg(value: str) -> str:
    value = value.strip()
    if value.startswith('"') and value.endswith('"'):
        value = value[1:-1]
    return re.sub(r'\\([\\"])', r'\1', value)


def prepare(scratch: Path, runtime: Path, control: Path, java: Path, jdwp: int, xmx: str) -> dict:
    """Validate actual MDG output, then retain per-runtime copies of its argfiles."""
    if not 1024 <= jdwp <= 65535 or not re.fullmatch(r'[1-9][0-9]*[MGmg]', xmx):
        raise ValueError('Invalid loopback JDWP port or memory size')
    native = scratch / 'build/moddev'
    sources = {name: native / name for name in ('serverRunClasspath.txt', 'serverRunVmArgs.txt', 'serverRunProgramArgs.txt')}
    texts = {name: path.read_text() for name, path in sources.items()}
    program = texts['serverRunProgramArgs.txt']
    if 'net.neoforged.fml.startup.Server' not in program or '--nogui' not in program:
        raise ValueError('MDG output is not the native headless server launcher')
    cp_lines = [line for line in texts['serverRunClasspath.txt'].splitlines() if line.strip() and not line.startswith('#')]
    if len(cp_lines) != 2 or cp_lines[0] not in ('-cp', '-classpath'):
        raise ValueError('Unexpected MDG native classpath format')
    paths = [Path(entry) for entry in java_arg(cp_lines[1]).split(os.pathsep)]
    if any(not path.exists() for path in paths):
        raise ValueError('Native classpath contains missing files')
    minecraft = [path for path in paths if path.name == 'minecraft-patched-26.3.0.90.jar']
    neo = [path for path in paths if path.name == 'neoforge-26.3.0.90-universal.jar']
    if len(minecraft) != 1 or len(neo) != 1:
        raise ValueError('Exact Minecraft 26.3 / NeoForge 26.3.0.90 classpath required')
    with zipfile.ZipFile(minecraft[0]) as archive:
        if json.loads(archive.read('version.json'))['id'] != '26.3':
            raise ValueError('Minecraft artifact does not contain exact version 26.3')
    for path in paths:
        if path.suffix == '.jar':
            with zipfile.ZipFile(path) as archive:
                if 'com/skycraft/Skycraft.class' in archive.namelist():
                    raise ValueError('A packaged old core must not override native development classes')
    compiled = scratch / 'build/classes/java/main'
    resources = scratch / 'build/resources/main'
    if compiled not in paths or resources not in paths or not resources.is_dir():
        raise ValueError('MDG classpath must include current development classes and processed resources')
    metadata = json.loads((resources / 'pack.mcmeta').read_text(encoding='utf8'))
    authored = json.loads((ROOT / 'mod/src/main/resources/pack.mcmeta').read_text(encoding='utf8'))
    if any(metadata.get('pack', {}).get(field) != authored['pack'][field] for field in ('min_format', 'max_format')) or metadata.get('filter') != authored.get('filter'):
        raise ValueError('Processed native pack metadata is stale or differs from authored format/filter')
    rules_path = Path('resourcepacks/recipe_rules/pack.mcmeta')
    rules = json.loads((resources / rules_path).read_text(encoding='utf8'))
    authored_rules = json.loads((ROOT / 'mod/src/main/resources' / rules_path).read_text(encoding='utf8'))
    if rules != authored_rules:
        raise ValueError('Processed native recipe-rules metadata is stale')
    core = (compiled / 'com/skycraft/Skycraft.class').read_bytes()
    if core[:4] != b'\xca\xfe\xba\xbe' or int.from_bytes(core[6:8], 'big') != 69:
        raise ValueError('Development core was not compiled for Java 25')
    props = [java_arg(line) for line in texts['serverRunVmArgs.txt'].splitlines()]
    expected_dev = '-Dskycraft.dev=' + str(control)
    if not any(line.replace('\\', '/').lower() == expected_dev.replace('\\', '/').lower() for line in props):
        raise ValueError('Prepared server control directory differs from requested runtime')
    launch = runtime / 'native-launch'
    launch.mkdir(parents=True, exist_ok=True)
    for name, path in sources.items():
        shutil.copyfile(path, launch / name)
    args = ['-Xmx' + xmx, f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{jdwp}',
            '@' + (launch / 'serverRunClasspath.txt').as_posix(), '@' + (launch / 'serverRunVmArgs.txt').as_posix(),
            '-Dskycraft.dev=' + control.as_posix(),
            '-Dfml.modFolders=skycraft%%' + compiled.as_posix() + ';skycraft%%' + resources.as_posix(),
            'net.neoforged.devlaunch.Main', '@' + (launch / 'serverRunProgramArgs.txt').as_posix()]
    return {'minecraft': '26.3', 'neoforge': '26.3.0.90', 'javaVersion': 25, 'java': str(java),
            'runtime': str(runtime), 'control': str(control), 'args': args}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--control', type=Path, required=True)
    parser.add_argument('--jdwp', type=int, required=True)
    parser.add_argument('--xmx', default='4G')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    scratch = Path(os.environ.get('SKYCRAFT_263_SCRATCH', r'E:\Skycraft-scratch\migration-26.3')).resolve()
    java_home = Path(os.environ.get('SKYCRAFT_JAVA25_HOME') or
                     Path(r'E:\Skycraft-scratch\toolchains\java25-location.txt').read_text().strip()).resolve(strict=True)
    java = java_home / 'bin/java.exe'
    runtime, control = args.runtime.resolve(strict=True), args.control.resolve(strict=True)
    environment = os.environ.copy()
    for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        environment.pop(name, None)
    result = subprocess.run([str(java), '-XshowSettings:properties', '-version'], capture_output=True, text=True, check=True, env=environment)
    if not re.search(r'java.specification.version\s*=\s*25\s', result.stderr):
        raise ValueError('Configured development JDK must be Java 25')
    subprocess.run([sys.executable, str(Path(__file__).with_name('build_263.py')), '--run-dir', str(runtime),
                    '--dev-dir', str(control), 'prepareServerRun', 'createServerLaunchScript'], check=True, env=environment)
    manifest = prepare(scratch, runtime, control, java, args.jdwp, args.xmx)
    args.output.write_text(json.dumps(manifest, indent=2) + '\n')
    print('Native server launch prepared; Minecraft was not started.')


if __name__ == '__main__':
    main()
