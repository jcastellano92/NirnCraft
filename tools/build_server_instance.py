"""Build the exact native full server ZIP without starting Minecraft or accepting its EULA.

The official, checksummed NeoForge installer runs offline server preparation only.
Every server/both mod is taken from the committed platform lock and hash checked.
Existing archives/worlds are preserved; candidate paths may live on the NVMe.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib
import urllib.request
import zipfile
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parent))
from validate_pack import ROOT, TARGET, validate_core_jar, validate_pack
import build_prism_instance as pack_tools

INSTALLER_URL = ('https://maven.neoforged.net/releases/net/neoforged/neoforge/'
                 '26.3.0.90/neoforge-26.3.0.90-installer.jar')
INSTALLER_SHA256 = '82f4d01453e1318887404efaf902a83cf5fdf067bacae2211f91119e247e9ce1'
SERVER_TARGET = {key: value for key, value in TARGET.items() if key != 'instanceName'}
NATIVE_ARGS = Path('libraries/net/neoforged/neoforge') / TARGET['neoforge']


def java_25(java):
    """Check the executable actually selected, never infer Java from its folder name."""
    java = Path(java).resolve(strict=True)
    environment = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        environment.pop(key, None)
    version = subprocess.run([str(java), '-XshowSettings:properties', '-version'],
                             capture_output=True, text=True, timeout=20, check=True, env=environment)
    if not re.search(r'(?m)^\s*java\.specification\.version\s*=\s*25\s*$', version.stderr + version.stdout):
        raise ValueError('The server installer and launcher require Java 25')
    return java, environment


def server_mods(lock):
    rows = [row for row in lock['active'] if row['side'] in ('server', 'both')]
    projects = {row.get('projectId'): row for row in rows if row.get('projectId')}
    keys = {row['key'] for row in rows}
    filenames = [row['filename'] for row in rows]
    if len(filenames) != len(set(filenames)):
        raise ValueError('Duplicate server artifact filenames in platform lock')
    for row in rows:
        for dependency in row['requiredDependencies']:
            if dependency.get('key') and dependency['key'] not in keys:
                raise ValueError(f"{row['key']}: required mod is excluded from the server: {dependency['key']}")
            if dependency.get('project_id') and dependency['project_id'] not in projects:
                raise ValueError(f"{row['key']}: required project is excluded from the server: {dependency['project_id']}")
    return rows


def fetch(path, url, expected, algorithm, cache):
    """Bounded network reads and atomic cache publication, with hashes on every reuse."""
    path, cache = Path(path), Path(cache)
    path.parent.mkdir(parents=True, exist_ok=True)
    cache.mkdir(parents=True, exist_ok=True)
    name = hashlib.sha256(url.encode()).hexdigest()[:16] + '_' + path.name
    cached = cache / name
    old_cached = ROOT / 'dist/.cache' / name
    if not cached.exists() and old_cached.is_file() and pack_tools.file_hash(old_cached, algorithm) == expected.lower():
        shutil.copyfile(old_cached, cached)
    if cached.exists() and pack_tools.file_hash(cached, algorithm) != expected.lower():
        raise ValueError(f'Existing cached artifact has an incorrect hash: {cached}')
    if not cached.exists():
        temporary = cached.with_name(cached.name + '.partial')
        if temporary.exists():
            raise ValueError(f'Previous partial download retained: {temporary}')
        with urllib.request.urlopen(urllib.request.Request(url, headers=pack_tools.HEADERS), timeout=60) as response, temporary.open('xb') as output:
            shutil.copyfileobj(response, output)
        if pack_tools.file_hash(temporary, algorithm) != expected.lower():
            raise ValueError(f'Download failed hash verification: {url}; retained {temporary}')
        temporary.rename(cached)
    shutil.copyfile(cached, path)
    if pack_tools.file_hash(path, algorithm) != expected.lower():
        raise ValueError(f'Copied artifact failed hash verification: {path}')


def baked_bundle(core):
    """Check the full, internally consistent bundle; live bake check remains mandatory."""
    with zipfile.ZipFile(core) as archive:
        manifest = json.loads(archive.read('skycraft/baked/manifest.json'))
        names = {name + '.bin' for name in ('landmass', 'roadplan', 'streetplan', 'sceneplan')}
        if manifest.get('format') != 1 or set(manifest.get('files', [])) != names:
            raise ValueError('Core does not contain all four native bakes')
        expected = manifest['inputs']
        if not re.fullmatch('[0-9a-f]{64}', expected):
            raise ValueError('Invalid embedded bake input hash')
        for name in names:
            raw = archive.read('skycraft/baked/' + name)
            length = int.from_bytes(raw[:2], 'big')
            if raw[2:2 + length].decode('ascii') != expected or not zlib.decompress(raw[2 + length:]):
                raise ValueError(f'Mixed, corrupt or empty embedded bake: {name}')
        return manifest


def declared_server_dependencies(server, rows):
    """Inspect actual bundled mod descriptors, including JarJar, for side closure.

    This does not certify binary mixin/API compatibility; a genuine full server
    startup and multiplayer connection remain separate acceptance gates.
    """
    declarations = []

    def inspect(raw, origin):
        with zipfile.ZipFile(raw) as archive:
            names = set(archive.namelist())
            metadata_name = next((n for n in ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml') if n in names), None)
            if metadata_name:
                metadata = tomllib.loads(archive.read(metadata_name).decode('utf-8'))
                for mod in metadata.get('mods', []):
                    declarations.append({'modId': mod['modId'], 'version': mod.get('version'), 'origin': origin,
                                         'dependencies': metadata.get('dependencies', {}).get(mod['modId'], [])})
            if 'META-INF/jarjar/metadata.json' in names:
                for nested in json.loads(archive.read('META-INF/jarjar/metadata.json')).get('jars', []):
                    inspect(io.BytesIO(archive.read(nested['path'])), origin + '!/' + nested['path'])

    for row in rows:
        inspect(server / 'mods' / row['filename'], row['filename'])
    available = {entry['modId'] for entry in declarations} | {'minecraft', 'neoforge'}
    for entry in declarations:
        for dependency in entry['dependencies']:
            required = dependency.get('type') == 'required' or dependency.get('mandatory', False)
            if required and dependency.get('side', 'BOTH') != 'CLIENT' and dependency['modId'] not in available:
                raise ValueError(f"{entry['origin']}: missing native server dependency {dependency['modId']}")
    return declarations


def check_install(server):
    for name in ('win_args.txt', 'unix_args.txt'):
        path = server / NATIVE_ARGS / name
        text = path.read_text(encoding='utf-8')
        if not all(re.search(r'(?m)^--fml[.]' + key + r'\s+' + re.escape(version) + r'(?:\s|$)', text)
                   for key, version in (('mcVersion', TARGET['minecraft']), ('neoForgeVersion', TARGET['neoforge']))):
            raise ValueError(f'Installer argument file does not identify the exact native target: {path}')
        if 'net.neoforged.fml.startup.Server' not in text:
            raise ValueError(f'Unexpected native server entry point: {path}')
    if not (server / 'libraries/net/neoforged/neoforge' / TARGET['neoforge']).is_dir():
        raise ValueError('Exact native NeoForge installation is missing')


def reuse_installation(archive_path, expected_sha256, server, log):
    """Reuse only libraries from an already built, hash-identified native package."""
    archive_path = Path(archive_path).resolve(strict=True)
    if not expected_sha256 or pack_tools.file_hash(archive_path, 'sha256') != expected_sha256:
        raise ValueError('Cached installation archive must match its recorded SHA256')
    with zipfile.ZipFile(archive_path) as archive:
        receipt = json.loads(archive.read('server/migration/server-receipt.json'))
        if receipt.get('target') != SERVER_TARGET or receipt.get('installer') != {'url': INSTALLER_URL, 'sha256': INSTALLER_SHA256}:
            raise ValueError('Cached platform installation has a different target or installer')
        planned, seen = [], set()
        for entry in archive.infolist():
            if not entry.filename.startswith('server/libraries/') or entry.is_dir():
                continue
            name = entry.filename.removeprefix('server/')
            relative = Path(name)
            if '\\' in name or '..' in relative.parts or ':' in name or relative.is_absolute() or relative in seen:
                raise ValueError('Unsafe or duplicate cached native library path')
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Cached native library must not be a symbolic link')
            seen.add(relative)
            planned.append((entry, server / relative))
        if not planned:
            raise ValueError('Cached native installation has no libraries')
        for entry, target in planned:
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(entry) as source, target.open('xb') as destination:
                shutil.copyfileobj(source, destination)
        log.write_bytes(archive.read('server/migration/installer.log'))
        for name in ('run.sh', 'run.bat'):
            original = 'server/migration/installer-launchers/' + name
            if original in archive.namelist():
                (server / name).write_bytes(archive.read(original))
    check_install(server)
    return {'archive': str(archive_path), 'sha256': expected_sha256}


def write_launchers(server):
    originals = server / 'migration/installer-launchers'
    originals.mkdir(parents=True, exist_ok=True)
    for name in ('run.sh', 'run.bat', 'run.ps1'):
        path = server / name
        if path.exists():
            shutil.copyfile(path, originals / name)
        content = (ROOT / 'tools/server' / name).read_text(encoding='utf-8').replace('@NEOFORGE@', TARGET['neoforge'])
        path.write_text(content, encoding='utf-8', newline='\r\n' if name == 'run.bat' else '\n')
        if name == 'run.sh':
            path.chmod(0o755)
    (server / 'user_jvm_args.txt').write_text('-Xms2G\n-Xmx6G\n-XX:+UseG1GC\n-XX:MaxGCPauseMillis=200\n', encoding='utf-8')
    (server / 'server.properties').write_text(
        '# Skycraft native defaults; preserve these when upgrading an existing world.\n'
        'motd=Skycraft - The Elder Scrolls in Minecraft\nlevel-seed=Nirn\ndifficulty=normal\n'
        'allow-flight=true\nmax-tick-time=120000\nview-distance=10\nsimulation-distance=8\n'
        'spawn-protection=0\nenable-command-block=false\nsync-chunk-writes=true\nonline-mode=true\npvp=true\n', encoding='utf-8')
    (server / 'README.txt').write_text(
        'Skycraft dedicated server: Minecraft 26.3 / NeoForge 26.3.0.90 / Java 25\n\n'
        'Extract the complete server directory to a new location. Keep old worlds and installations backed up.\n'
        'Read https://aka.ms/MinecraftEULA and create eula.txt containing eula=true only if you agree.\n'
        'Windows: run.bat (or run.ps1). Linux/macOS: ./run.sh nogui. These check Java 25 before starting.\n'
        'Choose Java with SKYCRAFT_JAVA (full executable path), JAVA_HOME, or PATH, in that order.\n'
        'Adjust memory in user_jvm_args.txt; defaults are 2 GB initial and 6 GB maximum.\n'
        'Fresh servers use Nirn, the authored seed with shipped plans. Keep an existing world\'s seed unchanged.\n'
        'Join through normal Minecraft Multiplayer using this exact client pack; local network discovery\n'
        'is for integrated LAN games. A dedicated server uses its address and port (default 25565).\n'
        'Use the console stop command and wait for all dimensions to save before closing.\n'
        'Old 1.20.1 saves require the documented offline conversion on a backup before loading.\n'
        'Custom shaders and Distant Horizons are preserved outside this server and remain disabled.\n'
        'migration/server-receipt.json records package integrity. Packaging alone is not runtime acceptance.\n', encoding='utf-8')


def build(core, output, java, work_root, cache, keep_work=False, base_archive=None, base_sha256=None):
    core, output = Path(core).resolve(strict=True), Path(output).resolve()
    if output.exists() or output.with_name(output.name + '.partial').exists():
        raise ValueError('Preserve existing packages; choose a new archive filename')
    lock = validate_pack()
    validate_core_jar(core)
    bakes = baked_bundle(core)
    java, environment = java_25(java)
    rows, entries = server_mods(lock), pack_tools.read_index()
    work_root, cache = Path(work_root).resolve(), Path(cache).resolve()
    work_root.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='skycraft-server-263-', dir=work_root))
    server = work / 'server'
    server.mkdir()
    print('Native server staging:', server, flush=True)
    reuse = None
    if base_archive:
        reuse = reuse_installation(base_archive, base_sha256, server, work / 'installer.log')
    else:
        installer = work / 'neoforge-installer.jar'
        fetch(installer, INSTALLER_URL, INSTALLER_SHA256, 'sha256', cache)
        with (work / 'installer.log').open('wb') as log:
            subprocess.run([str(java), '-Xmx1G', '-jar', str(installer), '--installServer'], cwd=server,
                           env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=900, check=True)
    check_install(server)
    selected_metadata = {row['metadata'] for row in rows}
    tasks = []
    for entry in entries:
        relative = Path(entry['file'])
        source = ROOT / 'pack' / relative
        if entry.get('metafile'):
            if entry['file'] not in selected_metadata:
                continue
            metadata = tomllib.loads(source.read_text(encoding='utf-8'))
            download = metadata['download']
            url = download.get('url') or pack_tools.curseforge_url(metadata['update']['curseforge']['file-id'], metadata['filename'])
            tasks.append((server / relative.parent / metadata['filename'], url, download['hash'], download['hash-format'], cache))
        else:
            destination = server / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda task: fetch(*task), tasks))
    shutil.copyfile(core, server / 'mods/skycraft-core.jar')
    expected = {row['filename'] for row in rows} | {'skycraft-core.jar'}
    if {p.name for p in (server / 'mods').glob('*.jar')} != expected:
        raise ValueError('Packaged server mod list differs from the exact side-filtered lock')
    declarations = declared_server_dependencies(server, rows)
    write_launchers(server)
    shutil.copyfile(work / 'installer.log', server / 'migration/installer.log')
    receipt = {'schema': 1, 'target': SERVER_TARGET, 'fullServerPack': True, 'runtimeVerified': False,
               'coreSha256': pack_tools.file_hash(core, 'sha256'), 'bakes': bakes,
               'installer': {'url': INSTALLER_URL, 'sha256': INSTALLER_SHA256}, 'installationReuse': reuse,
               'mods': [{key: row[key] for key in ('key', 'filename', 'side', 'hashFormat', 'hash')} for row in rows],
               'declaredMods': declarations,
               'note': 'Hashes and dependency-side closure verified; genuine full-mod server boot and multiplayer connection are separate gates.'}
    (server / 'migration/server-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_name(output.name + '.partial')
    with zipfile.ZipFile(partial, 'x', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(server.rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(work).as_posix())
    with zipfile.ZipFile(partial) as archive:
        if archive.testzip() is not None:
            raise ValueError('Server archive failed CRC verification')
    partial.rename(output)
    if not keep_work:
        # Delete only the unique temporary tree we created, under its checked parent.
        if work.parent != work_root or not work.name.startswith('skycraft-server-263-'):
            raise ValueError('Refusing to clean an unexpected staging path')
        shutil.rmtree(work)
    print(json.dumps({'archive': str(output), 'sha256': pack_tools.file_hash(output, 'sha256'),
                      'serverMods': len(rows), 'runtimeVerified': False, 'staging': str(work) if keep_work else None}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--core-jar', type=Path)
    parser.add_argument('--check-java', action='store_true', help='Check the selected Java 25 executable, then exit without installing or launching anything')
    parser.add_argument('--output-zip', type=Path, default=ROOT / 'dist/Skycraft-26.3-server.zip')
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--work-root', type=Path, default=ROOT / 'dist/server-build-263')
    parser.add_argument('--cache', type=Path, default=ROOT / 'dist/.cache')
    parser.add_argument('--keep-work', action='store_true', help='Retain the unique staging directory for coordinated runtime QA')
    parser.add_argument('--base-archive', type=Path, help='Reuse installed platform libraries from a previous exact native server ZIP')
    parser.add_argument('--base-sha256', help='Recorded SHA256 of --base-archive; required for verified library reuse')
    args = parser.parse_args()
    if args.check_java:
        java, _ = java_25(args.java)
        print(json.dumps({'java': str(java), 'javaVersion': 25, 'minecraftStarted': False}))
        return
    if args.core_jar is None:
        parser.error('--core-jar is required unless --check-java is selected')
    build(args.core_jar, args.output_zip, args.java, args.work_root, args.cache, args.keep_work, args.base_archive, args.base_sha256)


if __name__ == '__main__':
    main()
