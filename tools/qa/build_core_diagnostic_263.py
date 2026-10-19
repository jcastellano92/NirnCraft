"""Build a separate native core-only diagnostic archive; never install or launch it.

This cannot certify the full mod pack. The active full-pack lock/mods are untouched.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from validate_pack import TARGET, validate_core_jar
from prepare_prism_263 import java_25

NAME = 'NirnCraft-26.3-core-diagnostic'


def build(core: Path, output: Path, java: Path) -> None:
    core, output = core.resolve(strict=True), output.resolve()
    validate_core_jar(core)
    java = java_25(java)
    if output.exists():
        raise ValueError('Preserve existing diagnostic evidence; choose a new output filename')
    output.parent.mkdir(parents=True, exist_ok=True)
    config = '\n'.join(['InstanceType=OneSix', 'name=' + NAME, 'iconKey=default', 'OverrideJavaLocation=true',
                        'JavaPath=' + java.as_posix(), 'JavaVersion=25', 'OverrideMemory=true',
                        'MinMemAlloc=2048', 'MaxMemAlloc=4096', 'OverrideWindow=true',
                        'MinecraftWinWidth=1280', 'MinecraftWinHeight=800', 'LaunchMaximized=false', ''])
    metadata = {'diagnosticOnly': True, 'fullPackAcceptance': False,
                'target': {key: value for key, value in TARGET.items() if key != 'instanceName'},
                'instanceName': NAME, 'coreSha256': hashlib.sha256(core.read_bytes()).hexdigest(),
                'contents': 'Actual native core only; no performance/library mods or shader packs.',
                'activeFullPackChanged': False, 'launched': False}
    temporary = output.with_suffix(output.suffix + '.partial')
    if temporary.exists():
        raise ValueError('Existing partial archive retained; choose another output filename')
    try:
        with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(NAME + '/instance.cfg', config)
            archive.writestr(NAME + '/mmc-pack.json', json.dumps({'formatVersion': 1, 'components': [
                {'uid': 'net.minecraft', 'version': TARGET['minecraft']},
                {'uid': 'net.neoforged', 'version': TARGET['neoforge']}]}))
            archive.write(core, NAME + '/.minecraft/mods/nirncraft-core.jar')
            archive.writestr(NAME + '/.minecraft/migration/core-diagnostic.json', json.dumps(metadata, indent=2))
            archive.writestr(NAME + '/.minecraft/options.txt',
                             'onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:8\nmaxFps:60\nfullscreen:false\n')
        with zipfile.ZipFile(temporary) as archive:
            if archive.testzip() is not None:
                raise ValueError('Diagnostic archive failed integrity verification')
        temporary.rename(output)
    except Exception:
        # Preserve a failed partial archive for inspection; never modify an existing instance.
        raise
    print(json.dumps({'archive': str(output), **metadata}, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--core-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    args = parser.parse_args()
    build(args.core_jar, args.output, args.java)


if __name__ == '__main__':
    main()
