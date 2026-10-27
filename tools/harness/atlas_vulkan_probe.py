"""One opt-in shared-device Vulkan correctness probe; run only through the harness job gate."""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
import uuid
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
EVIDENCE_ROOT = Path('E:/NirnCraft-scratch/codex-vulkan-evidence')
JCMD = Path(os.environ['JAVA25_HOME']) / 'bin/jcmd.exe' if os.environ.get('JAVA25_HOME') else None
sys.path.insert(0, str(HERE))
import game
import atlas_static
import atlas_tools
import process_memory
import probe_flags


def native_diagnostic_environment(env, enabled, jcmd=JCMD):
    env = dict(env)
    env.pop('NIRNCRAFT_ATLAS_PROBE_JVM_NMT', None)
    if not enabled: return env
    if jcmd is None or not Path(jcmd).is_file(): raise RuntimeError('configured JDK 25 jcmd is unavailable; set JAVA25_HOME')
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        if re.search(r'-XX:NativeMemoryTracking(?:=|\b)', env.get(key, '')):
            raise RuntimeError('existing NativeMemoryTracking option conflicts with the probe')
    env['NIRNCRAFT_ATLAS_PROBE_JVM_NMT'] = '1'
    return env


def reject_existing_native_tracking(cfg):
    data = Path(cfg).read_bytes()
    marker = probe_flags.block(data)
    line = next(line for line in data.splitlines() if line.startswith(b'JvmArgs='))
    if marker is not None: line = line.replace(marker, b'', 1)
    if re.search(rb'(?:^|[\s"])-XX:NativeMemoryTracking(?:=|\b)', line):
        raise RuntimeError('existing raw NativeMemoryTracking option conflicts with the probe')


def abi_logged(raw, version="0.18.0"):
    return bool(re.search(r"atlas_core [^\n]*\(abi\s+" + re.escape(version) + r"(?:[;)\s])", raw))


def memory_sample(evidence, pids=(), phase='running'):
    row = process_memory.sample(pids)
    row['phase'] = phase
    with (Path(evidence) / 'memory.jsonl').open('a', encoding='utf-8') as stream:
        stream.write(json.dumps(row) + '\n')
    return row


def require_live_memory_policy(row):
    # This is the existing scheduler's live-game allowance, not a new renderer budget.
    # Fail before any launch/option change if a mislabeled invocation got the build cap.
    limit = row.get('job', {}).get('limitBytes')
    if limit is None or limit < 24 * 2**30:
        raise RuntimeError('live job memory policy unverified: run through agent_job --loop middle|outer with game classification')
    process_memory.require_accounting(row)
    if process_memory.pressure(row):
        raise RuntimeError('initial job commit reached the sampled 85% reserve; refusing launch')


def gl_path_observed(fresh_log, profile):
    # The latest renderer transition must be on; historical on cannot supersede a later stop.
    transition = re.findall(r'(?:Infinite ATLAS far field (on|off)\b|Infinite ATLAS (stopped)\b)', fresh_log)
    active = bool(transition) and transition[-1][0] == 'on'
    return (active and bool(re.search(r'Using graphics backend OpenGL\b', fresh_log, re.I))
            and bool(re.search(r'"interop":"(?:zero-copy|host-copy)', profile)))


def gl_ready_gpu_profile_observed(fresh_log, profile):
    # ready_sample requires a positive measured atlasMs, pipeline completion and valid renderer quality/reach.
    return gl_path_observed(fresh_log, profile) and atlas_static.ready_sample(profile)


def fingerprint_observations(fresh_log):
    result = []
    for line in fresh_log.splitlines():
        if "atlas coarse bake: the cache set's key:" not in line and 'gen fingerprint ' not in line:
            continue
        fields = {name: None for name in ('build', 'program', 'ingest', 'map', 'extra', 'stages', 'whole', 'stage_count')}
        for name in ('build', 'program', 'ingest', 'map', 'extra', 'stages', 'whole'):
            match = re.search(r'\b' + name + r'\s+([0-9a-fA-F]{16})\b', line)
            if match: fields[name] = match.group(1).lower()
        count = re.search(r'\bstages\s+[0-9a-fA-F]{16}\s+\((\d+)\)', line)
        if count: fields['stage_count'] = int(count.group(1))
        result.append({'line': line, 'parts': fields})
    return result


def latch_ready(result, key, profile, elapsed):
    result['checks'][key] = True
    result['readiness_observation'] = {'key': key, 'profile': profile, 'elapsed_seconds': round(elapsed, 2)}


def reload_environment(env, transport, enabled):
    env = dict(env)
    env.pop('NIRNCRAFT_ATLAS_PROBE_RESOURCE_RELOAD', None)
    if enabled:
        if transport != 'gl': raise ValueError('resource reload probe requires GL transport')
        env['NIRNCRAFT_ATLAS_PROBE_RESOURCE_RELOAD'] = '1'
    return env


def recreated_gl_marker(reload_log):
    keys = fingerprint_observations(reload_log)
    if not re.search(r'Infinite ATLAS far field on\b', reload_log) or 'atlas: ingest components ' not in reload_log:
        return None
    return next((key for key in reversed(keys) if all(value is not None for value in key['parts'].values())), None)


def recreated_gl_ready(reload_log, boot_log, profile):
    marker = recreated_gl_marker(reload_log)
    fp = re.search(r'"genCache"\s*:\s*\{[^}]*"fingerprint"\s*:\s*"([0-9a-fA-F]{16})"', profile)
    return bool(marker and fp and fp.group(1).lower() == marker['parts']['whole']
                and gl_ready_gpu_profile_observed(boot_log, profile))


def reload_gl_resources(profile, latest, deadline):
    before = latest.read_bytes()
    offset = profile.stat().st_size if profile.exists() else 0
    remaining = int(deadline - time.monotonic())
    if remaining < 65: raise RuntimeError('insufficient bounded time for resource reload')
    started = time.monotonic()
    game.actions(['resources'], timeout=min(60, remaining - 20))
    observed_marker = None
    while time.monotonic() < deadline - 12:
        raw = latest.read_bytes()
        if not raw.startswith(before): raise RuntimeError('game log replaced during resource reload')
        changed = raw[len(before):].decode('utf-8', errors='replace')
        marker = recreated_gl_marker(changed)
        if marker and marker != observed_marker:
            observed_marker = marker
            # An old-context ready line may have been written while resources reloaded. Require a later sample,
            # after observing the new context's complete key, even when the new and old fingerprints are equal.
            offset = profile.stat().st_size if profile.exists() else 0
        lines = atlas_static.profile_lines(profile, offset)
        line = lines[-1] if lines else ''
        print('TRACE progress resource reload', int(time.monotonic() - started),
              (re.search(r'ready\{[^}]*\}', line) or ['no fresh profile'])[0], flush=True)
        if observed_marker and recreated_gl_ready(changed, raw.decode('utf-8', errors='replace'), line):
            return {'profile': line, 'elapsed_seconds': round(time.monotonic() - started, 2),
                    'fingerprints': fingerprint_observations(changed),
                    'fresh_context_and_ingest_observed': True}
        time.sleep(min(3, max(0, deadline - 12 - time.monotonic())))
    raise RuntimeError('no recreated GL context, ingest and ready profile after resource reload')


def prepare_gl_pose(files, evidence):
    if not files: raise RuntimeError('kept world has no existing player')
    evidence = Path(evidence)
    backup = evidence / 'player-before'; backup.mkdir()
    manifest = evidence / 'player-pose.json'
    record = {'state': 'INCOMPLETE', 'files': []}
    def save():
        game.lw.atomic(manifest, json.dumps(record, indent=2).encode('utf-8'))
    save()
    x, z, _ = atlas_tools.START
    try:
        for index, f in enumerate(files):
            raw = Path(f).read_bytes()
            before = backup / (str(index) + '.dat')
            game.lw.atomic(before, raw)
            entry = {'file': str(f), 'backup': str(before), 'state': 'BACKED_UP',
                     'new_position': [x + 0.5, 200.0, z + 0.5],
                     'new_yaw': atlas_tools.HEADING, 'new_pitch': 0.0}
            record['files'].append(entry)
            save()  # recovery information exists before the first byte of this player is changed
            old = atlas_tools.set_player_pose(f, x + 0.5, 200.0, z + 0.5, atlas_tools.HEADING, 0.0)
            entry['old_position'] = list(old)
            entry['state'] = 'UPDATED_AND_VERIFIED'
            save()
        record['state'] = 'COMPLETE'
        save()
    except Exception as exc:
        record['error'] = str(exc)
        save()
        raise
    return record['files']


def cache_inventory(game_dir):
    """Metadata only, called while the client is closed; never prune or modify cached data."""
    root = Path(game_dir) / 'atlas-cache'
    result = {'cache': str(root), 'sets': [], 'identity': {}}
    keep = root / 'keep-gen-sets'
    result['retainedDevelopmentSets'] = keep.read_text().strip() if keep.exists() else None
    for folder in sorted(root.glob('*/gen-*')):
        if not folder.is_dir(): continue
        files = list(folder.rglob('*.gch'))
        result['sets'].append({'name': folder.relative_to(root).as_posix(), 'recordsFiles': len(files),
                               'bytes': sum(f.stat().st_size for f in files)})
    jar = Path(game_dir) / 'mods/nirncraft-core.jar'
    with zipfile.ZipFile(jar) as core:
        for name in ('generator-identity.txt', 'far-identity.txt'):
            try: result['identity'][name] = core.read('nirncraft/' + name).decode().strip()
            except KeyError: result['identity'][name] = None
    return result


def record_cache_inventory(evidence, phase):
    state = cache_inventory(game.GAME)
    (Path(evidence) / ('cache-state-' + phase + '.json')).write_text(json.dumps(state, indent=2), encoding='utf-8')


def fresh_cache_environment(env, evidence, enabled):
    env = dict(env)
    env.pop('NIRNCRAFT_ATLAS_PROBE_CACHE_ROOT', None)
    if not enabled: return env, None
    evidence = Path(evidence).resolve()
    root = evidence / 'generated-cache'
    root.mkdir(exist_ok=False)
    if root.resolve().parent != evidence: raise RuntimeError('cache root escaped evidence directory')
    (root / 'keep-gen-sets').write_text('3\n', encoding='utf-8')
    value = root.as_posix()
    if ',' in value: raise RuntimeError('cache path cannot contain a comma')
    env['NIRNCRAFT_HARNESS_JVM'] = ','.join(filter(None, [env.get('NIRNCRAFT_HARNESS_JVM', ''),
                                                           'nirncraft.atlas.cacheDir=' + value]))
    env['NIRNCRAFT_ATLAS_PROBE_CACHE_ROOT'] = value
    return env, root


def record_generator_cache_inventory(evidence, root, phase):
    root = Path(root)
    files = [p for p in root.rglob('*.gch') if p.is_file()]
    state = {'root': str(root), 'recordsFiles': len(files), 'bytes': sum(p.stat().st_size for p in files),
             'keepGenSets': (root / 'keep-gen-sets').read_text().strip() if (root / 'keep-gen-sets').exists() else None}
    (Path(evidence) / ('generated-cache-state-' + phase + '.json')).write_text(json.dumps(state, indent=2), encoding='utf-8')


def cache_path_logged(raw, root):
    if not root: return False
    normalize = lambda value: value.strip().strip('"').replace('\\', '/').rstrip('/').casefold()
    expected = normalize(str(root))
    return any(normalize(line.split('self-baking cache in ', 1)[1]) == expected
               for line in raw.splitlines() if 'self-baking cache in ' in line)


def diagnostic_environment(env, evidence, enabled):
    env = dict(env)
    if enabled:
        env['NIRNCRAFT_HARNESS_JVM'] = ','.join(filter(None, [env.get('NIRNCRAFT_HARNESS_JVM', ''),
                                                             'nirncraft.atlas.memoryDiagnostics=true']))
        env['ATLAS_MEMORY_DIAGNOSTICS'] = '1'
        # Prism/Qt INI processing consumes backslash escapes in JvmArgs. Forward slashes preserve the path in
        # both the launcher's marker and the authoritative properties file on Windows.
        env['NIRNCRAFT_ATLAS_DUMP'] = str(Path(evidence) / 'generator-dump').replace('\\', '/')
    return env


def transport_environment(env, transport):
    if transport not in ('shared', 'host', 'gl'):
        raise ValueError('unknown Vulkan probe transport')
    env = dict(env)
    env['NIRNCRAFT_ATLAS_PROBE_TRANSPORT'] = transport
    extra = ['nirncraft.atlas.vkProbe=false']
    if transport != 'gl': extra.insert(0, 'nirncraft.atlas.vkSharedSync=' + ('true' if transport == 'shared' else 'false'))
    env['NIRNCRAFT_HARNESS_JVM'] = ','.join(filter(None, [env.get('NIRNCRAFT_HARNESS_JVM', ''), *extra]))
    return env


def worker(evidence, deadline):
    evidence = Path(evidence)
    groups = json.loads((HERE / 'scenes/groups.json').read_text(encoding='utf-8'))
    profile = game.GAME / 'atlas-profile.log'
    latest = game.GAME / 'logs/latest.log'
    previous_log = latest.read_bytes() if latest.exists() else b''
    requested_cache = os.environ.get('NIRNCRAFT_ATLAS_PROBE_CACHE_ROOT')
    offset = profile.stat().st_size if profile.exists() else 0
    start = time.monotonic()
    transport = os.environ.get('NIRNCRAFT_ATLAS_PROBE_TRANSPORT', 'shared')
    resource_reload = os.environ.get('NIRNCRAFT_ATLAS_PROBE_RESOURCE_RELOAD') == '1'
    if resource_reload and transport != 'gl': raise RuntimeError('resource reload requires GL transport')
    result = {'state': 'UNVERIFIED', 'evidence': str(evidence),
              'resource_reload_requested': resource_reload,
              'transport_requested': os.environ.get('NIRNCRAFT_ATLAS_PROBE_TRANSPORT', 'shared'),
              'requested_generator_cache_root': requested_cache,
              'checks': {}, 'unsupported': [
        'visual HUD, entity, translucent and distant terrain correctness require independent screenshot review',
        'entity and translucent subject presence is not guaranteed by the kept-world pose',
        'resize, sun replacement, disconnect and relaunch are not exercised',
        'validation layer availability and zero validation errors require separate evidence']}
    print('TRACE probe evidence', evidence, flush=True)
    old_launch = game.launch
    def bounded_launch(group, config, limit=900, attempt=1):
        remaining = int(deadline - time.monotonic())
        if remaining <= 0:
            raise RuntimeError('deadline expired before launch')
        return old_launch(group, config, limit=min(limit, remaining), attempt=2)
    game.launch = bounded_launch
    original_flags_writer = game._set_group_flags
    def journaled_flags(jvm):
        probe_flags.guard_before_write(game.lw.INSTANCE / 'instance.cfg', game.FLAGS_FILE, evidence)
        raw_args = ('-XX:NativeMemoryTracking=summary',) if os.environ.get('NIRNCRAFT_ATLAS_PROBE_JVM_NMT') == '1' else ()
        probe_flags.journal(jvm, evidence, game.lw.atomic, raw_args)
        original_flags_writer(jvm, raw_args)
    game._set_group_flags = journaled_flags
    try:
        game.assert_prepared()
        os.environ['NIRNCRAFT_HARNESS_NO_BUILD'] = '1'
        print('TRACE begin', transport, 'launch', flush=True)
        game.ensure('atlas', groups)
        print('TRACE ready client', flush=True)
        x, z, _ = atlas_tools.START
        remaining = int(deadline - time.monotonic())
        if remaining < 25:
            raise RuntimeError('insufficient time for camera and captures')
        game.actions(['ui close', 'perspective first', 'hud on', 'try time set 6000', 'weather-off on',
                      f'visit {x} {z} 3 {atlas_tools.HEADING} 6 clear'], timeout=min(45, remaining))
        while time.monotonic() < deadline - 20:
            samples = atlas_static.profile_lines(profile, offset)
            line = samples[-1] if samples else ''
            print('TRACE progress readiness', int(time.monotonic() - start),
                  (re.search(r'ready\{[^}]*\}', line) or ['no fresh profile'])[0], flush=True)
            live_raw = latest.read_bytes() if latest.exists() else b''
            live_fresh = live_raw[len(previous_log):] if live_raw.startswith(previous_log) else live_raw
            live_text = live_fresh.decode('utf-8', errors='replace')
            if transport == 'gl':
                ready = gl_ready_gpu_profile_observed(live_text, line)
                key = 'ready_gpu_profile_observed'
            else:
                ready = bool(atlas_static.ready_sample(line) and re.search(r'delivered=[1-9]\d*', line))
                key = 'ready_and_delivered'
            if ready:
                latch_ready(result, key, line, time.monotonic() - start)
                break
            time.sleep(5)
        if not result['checks'].get('ready_gpu_profile_observed' if transport == 'gl' else 'ready_and_delivered'):
            raise RuntimeError('no fresh ready GPU profile before deadline')
        for name, px in [('before', x), ('after', x if resource_reload else x + 2)]:
            if resource_reload and name == 'after':
                prior_keys = fingerprint_observations(latest.read_text(encoding='utf-8', errors='replace'))
                result['resource_reload_observation'] = reload_gl_resources(profile, latest, deadline)
                next_keys = result['resource_reload_observation']['fingerprints']
                result['checks']['resource_reload_stable_fingerprint'] = bool(prior_keys and next_keys) and prior_keys[-1]['parts'] == next_keys[-1]['parts']
            remaining = int(deadline - time.monotonic())
            if remaining < 8:
                raise RuntimeError('deadline before ' + name + ' capture')
            scene = {'id': 'vk-probe-' + name, 'timeout': min(remaining - 2, 30)}
            shot, rec, _, extra = game.capture(scene, 'vanilla',
                [f'visit {px} {z} 3 {atlas_tools.HEADING} 6 clear']
                + (['try time set 6000'] if resource_reload else []) + ['hud on', 'wait 10'],
                lambda tag: [f'capture {tag}'])
            shutil.copy2(shot, evidence / (name + '.png'))
            (evidence / (name + '.json')).write_text(json.dumps({'receipt': rec, 'extra': extra}, indent=2), encoding='utf-8')
            print('TRACE captured', name, flush=True)
        if resource_reload:
            poses = [json.loads((evidence / (name + '.json')).read_text())['receipt'] for name in ('before', 'after')]
            fields = ('position', 'eye', 'yaw', 'pitch', 'fov', 'dimension', 'renderDistance', 'dayTime', 'weather')
            result['checks']['resource_reload_capture_pose_equal'] = all(
                field in poses[0] and field in poses[1] and poses[0][field] == poses[1][field] for field in fields)
    except Exception as exc:
        result['error'] = str(exc)
        print('UNVERIFIED:', exc, flush=True)
    finally:
        # Snapshot live evidence before close; preserve full files, including validation if present.
        for src, name in [(profile, 'atlas-profile.log'), (game.GAME / 'logs/latest.log', 'latest.log'),
                          (game.GAME / 'validation.log', 'validation.log')]:
            if src.exists():
                shutil.copy2(src, evidence / name)
        raw = (evidence / 'latest.log').read_text(encoding='utf-8', errors='replace') if (evidence / 'latest.log').exists() else ''
        raw_bytes = (evidence / 'latest.log').read_bytes() if (evidence / 'latest.log').exists() else b''
        fresh_bytes = raw_bytes[len(previous_log):] if raw_bytes.startswith(previous_log) else raw_bytes
        result['cache_log_basis'] = ('unchanged' if raw_bytes == previous_log else
                                     'appended' if raw_bytes.startswith(previous_log) else 'replaced-or-truncated')
        result['checks']['requested_generator_cache_path_observed'] = cache_path_logged(
            fresh_bytes.decode('utf-8', errors='replace'), requested_cache) if requested_cache else None
        samples = atlas_static.profile_lines(profile, offset)
        joined = '\n'.join(samples)
        fresh_text = fresh_bytes.decode('utf-8', errors='replace')
        if transport == 'gl':
            result['checks']['latest_ready_gpu_profile_observed'] = bool(samples) and gl_ready_gpu_profile_observed(fresh_text, samples[-1])
        result['generator_fingerprint_observations'] = fingerprint_observations(fresh_text)
        result['cached_chunk_observations'] = [int(n) for n in re.findall(r'cached chunks (\d+)', joined)]
        result['checks'].update({
            'minecraft_vulkan_shared_path': bool(re.search(r'on Minecraft.s Vulkan backend, path VK_SHARED', raw)),
            'minecraft_vulkan_host_path': bool(re.search(r'on Minecraft.s Vulkan backend, path VK_HOST', raw)),
            'minecraft_device': '"onMinecraftDevice":true' in joined,
            'own_device': '"onMinecraftDevice":false' in joined,
            'shared_interop': '"interop":"vulkan shared device (zero-copy)"' in joined,
            'host_interop': '"interop":"vulkan host-copy (own device)"' in joined,
            'abi_0_18': abi_logged(fresh_text),
            'abi_0_17': abi_logged(fresh_text, '0.17.0'),
            'gl_interop_gpu': bool(re.search(r'\"interop\":\"(?:zero-copy|host-copy)', joined)),
            'gl_backend_and_gpu_interop_observed': transport == 'gl' and gl_path_observed(fresh_text, joined),
            'before_and_after_screens': all((evidence / (name + '.png')).exists() for name in ('before', 'after')),
        })
        result['elapsed_seconds'] = round(time.monotonic() - start, 2)
        (evidence / 'worker-receipt.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print('UNVERIFIED: independent visual review and unsupported checks remain; receipt', evidence / 'worker-receipt.json', flush=True)
    return 2


def clients(timeout=5):
    # Never return command lines: they may contain launcher credentials.
    pattern = re.escape(game.lw.INSTANCE.as_posix()).replace('/', r'[/\\]') + r'[/\\]'
    script = r"""$p = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'PATTERN'
    } | ForEach-Object { [pscustomobject]@{pid=$_.ProcessId; created=$_.CreationDate.ToString('o'); exe=$_.ExecutablePath} });
    ConvertTo-Json -InputObject $p -Compress""".replace('PATTERN', pattern.replace("'", "''"))
    r = subprocess.run(['powershell', '-NoProfile', '-Command', script], capture_output=True,
                       text=True, check=True, timeout=timeout)
    return json.loads(r.stdout or '[]')


def launcher_patterns():
    name = re.escape(game.lw.INSTANCE_NAME)
    directory = re.escape(str(game.lw.PRISM_ROOT))
    launch = r'(?i)(?:^|\s)--launch\s+(?:"' + name + r'"|' + name + r')(?=\s|$)'
    root = r'(?i)(?:^|\s)--dir\s+(?:"' + directory + r'"|' + directory + r')(?=\s|$)'
    return launch, root


def launcher_processes(timeout=3):
    # Return only identity and argument-match booleans; command lines can contain credentials.
    launch, root = launcher_patterns()
    script = r"""$p = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -ieq 'prismlauncher.exe' } |
      ForEach-Object { [pscustomobject]@{pid=$_.ProcessId; created=$_.CreationDate.ToString('o');
      exe=$_.ExecutablePath; launchMatch=($_.CommandLine -match '__LAUNCH__' -and
      [regex]::Matches($_.CommandLine, '(?i)(?:^|\s)--launch(?=\s|$)').Count -eq 1);
      dirMatch=($_.CommandLine -match '__ROOT__' -and
      [regex]::Matches($_.CommandLine, '(?i)(?:^|\s)--dir(?=\s|$)').Count -eq 1)} });
      ConvertTo-Json -InputObject $p -Compress"""
    script = script.replace('__LAUNCH__', launch.replace("'", "''")).replace('__ROOT__', root.replace("'", "''"))
    r = subprocess.run(['powershell', '-NoProfile', '-Command', script], capture_output=True,
                       text=True, check=True, timeout=timeout)
    return json.loads(r.stdout or '[]')


def launcher_member(pid):
    sample = process_memory.sample([pid])
    process_memory.require_accounting(sample)
    return len([p for p in sample['processes'] if p.get('pid') == pid and p.get('inJob')]) == 1


def stop_owned_launcher(p, timeout):
    launch, root = launcher_patterns()
    pid = int(p['pid'])
    script = r"""$p=Get-CimInstance Win32_Process -Filter 'ProcessId=__PID__';
    if ($null -eq $p) { exit 0 }
    if ($p.Name -ine 'prismlauncher.exe' -or $p.CreationDate.ToString('o') -ne '__CREATED__' -or
        $p.ExecutablePath -ne '__EXE__' -or $p.CommandLine -notmatch '__LAUNCH__' -or $p.CommandLine -notmatch '__ROOT__' -or
        [regex]::Matches($p.CommandLine, '(?i)(?:^|\s)--launch(?=\s|$)').Count -ne 1 -or
        [regex]::Matches($p.CommandLine, '(?i)(?:^|\s)--dir(?=\s|$)').Count -ne 1) { exit 4 }
    Stop-Process -Id __PID__ -Force -ErrorAction Stop"""
    for key, value in [('__CREATED__', p['created']), ('__EXE__', p['exe']), ('__LAUNCH__', launch), ('__ROOT__', root)]:
        script = script.replace(key, value.replace("'", "''"))
    script = script.replace('__PID__', str(pid))
    subprocess.run(['powershell', '-NoProfile', '-Command', script], timeout=timeout, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


class LauncherGuard:
    def __init__(self, ack, started_wall, exe):
        self.ack, self.started_wall = Path(ack), started_wall
        self.exe = os.path.normcase(os.path.realpath(exe))
        self.pinned = None
        self.ack_state = None

    def observe(self, deadline, reserve=0):
        if not self.ack.exists(): return
        try: ack = json.loads(self.ack.read_text(encoding='utf-8'))
        except (OSError, ValueError) as exc: raise RuntimeError('launcher acknowledgment unreadable') from exc
        state = ack.get('state')
        if self.ack_state is not None and state != self.ack_state:
            raise RuntimeError('launcher acknowledgment changed')
        self.ack_state = state
        if state in ('prevented', 'failed'): return
        if state != 'requested' or not isinstance(ack.get('launcherPid'), int) or ack['launcherPid'] <= 0:
            raise RuntimeError('launcher acknowledgment invalid')
        if self.pinned is not None:
            if ack['launcherPid'] != self.pinned['pid']: raise RuntimeError('launcher acknowledgment changed')
            return
        processes = launcher_processes(budget(deadline, 3, reserve))
        if len(processes) != 1 or processes[0]['pid'] != ack['launcherPid']:
            raise RuntimeError('acknowledged launcher identity unavailable or ambiguous')
        p = processes[0]
        if (not fresh_identity(p, self.started_wall) or not p.get('launchMatch') or not p.get('dirMatch') or
                os.path.normcase(os.path.realpath(p['exe'])) != self.exe or not launcher_member(p['pid'])):
            raise RuntimeError('launcher ownership unverified')
        self.pinned = p

    def verify_absent(self, deadline, reserve=0):
        self.observe(deadline, reserve)
        try: ack = json.loads(self.ack.read_text(encoding='utf-8'))
        except (OSError, ValueError) as exc: raise RuntimeError('launcher acknowledgment unavailable') from exc
        if ack.get('state') not in ('prevented', 'failed') and self.pinned is None:
            raise RuntimeError('launcher was not pinned')
        processes = launcher_processes(budget(deadline, 3, reserve))
        if processes: raise RuntimeError('Prism remains or appeared; restoration unsafe')

    def finish(self, deadline):
        self.observe(deadline, 8)
        processes = launcher_processes(budget(deadline, 3, 8))
        if not processes:
            self.verify_absent(deadline, 7)
            return False
        if (len(processes) != 1 or self.pinned is None or identity(processes[0]) != identity(self.pinned) or
                not processes[0].get('launchMatch') or not processes[0].get('dirMatch') or
                not launcher_member(self.pinned['pid'])):
            raise RuntimeError('launcher termination refused: ownership changed')
        # Only after Minecraft is closed, and only the pinned launcher; prefer its natural exit first.
        time.sleep(min(0.5, budget(deadline, 0.5, 8)))
        processes = launcher_processes(budget(deadline, 3, 8))
        forced = False
        if processes:
            if len(processes) != 1 or identity(processes[0]) != identity(self.pinned) or not launcher_member(self.pinned['pid']):
                raise RuntimeError('launcher termination refused: identity changed')
            stop_owned_launcher(self.pinned, budget(deadline, 3, 6))
            forced = True
        self.verify_absent(deadline, 5)
        return forced


def restore_after_launcher(guard, options, prior, forced, evidence, deadline, result):
    guard.verify_absent(deadline, 6)
    cfg, flags = game.lw.INSTANCE / 'instance.cfg', game.FLAGS_FILE
    probe_flags.restore(cfg, flags, evidence, True, game.lw.atomic)
    lines = options.read_bytes().splitlines(keepends=True)
    indices = [i for i, line in enumerate(lines) if line.startswith(b'preferredGraphicsBackend:')]
    if len(indices) != 1 or lines[indices[0]] != forced: raise RuntimeError('backend line changed during run')
    lines[indices[0]] = prior
    options.write_bytes(b''.join(lines))
    restored = (cfg.read_bytes(), flags.read_bytes() if flags.exists() else None, options.read_bytes())
    time.sleep(min(0.5, budget(deadline, 0.5, 5)))
    guard.verify_absent(deadline, 4)
    if restored != (cfg.read_bytes(), flags.read_bytes() if flags.exists() else None, options.read_bytes()):
        raise RuntimeError('launcher settings changed after restoration')
    result['probe_flags_restored'] = True
    result['backend_option_restored'] = True
    result['launcher_absent_before_after_restore'] = True


def budget(deadline, cap, reserve=0):
    remaining = min(cap, deadline - time.monotonic() - reserve)
    if remaining <= 0:
        raise TimeoutError('probe deadline exhausted')
    return remaining


def snapshot(evidence):
    for src, name in [(game.GAME / 'atlas-profile.log', 'atlas-profile.log'),
                      (game.GAME / 'logs/latest.log', 'latest.log'),
                      (game.GAME / 'validation.log', 'validation.log')]:
        try:
            if src.exists(): shutil.copy2(src, Path(evidence) / name)
        except OSError as exc:
            print('TRACE snapshot failed', name, type(exc).__name__, flush=True)


def fresh_identity(p, started_wall):
    try:
        return (p['exe'] and datetime.fromisoformat(p['created'].replace('Z', '+00:00')).timestamp() >= started_wall
                and int(p['pid']) > 0)
    except (KeyError, ValueError, TypeError):
        return False


def identity(p):
    return (p['pid'], p['created'], p['exe'])


def launch_resolved(ack, seen, current):
    try:
        state = json.loads(ack.read_text(encoding='utf-8')).get('state')
    except (OSError, ValueError, AttributeError):
        return False
    return not current and (state in ('prevented', 'failed') or (state == 'requested' and bool(seen)))


def stop_helper(proc, deadline):
    if proc.poll() is None:
        proc.terminate()
        proc.wait(timeout=budget(deadline, 3))


def cancel_and_stop(cancel, proc, deadline):
    cancel.touch()
    if proc:
        stop_helper(proc, deadline)


def bounded_snapshot(evidence, deadline, result):
    timeout = budget(deadline, 5, 2)
    snap = subprocess.Popen([sys.executable, '-u', __file__, '--snapshot-worker', str(evidence)])
    try:
        snap.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        stop_helper(snap, deadline)
        result['snapshot_timeout'] = True


def stop_owned(identity, timeout=5):
    # Query and terminate atomically within one PowerShell invocation; reject PID reuse,
    # executable changes, or a changed exact-instance command line. Never print commands.
    pattern = re.escape(game.lw.INSTANCE.as_posix()).replace('/', r'[/\\]') + r'[/\\]'
    pid = int(identity['pid'])
    created = identity['created'].replace("'", "''")
    exe = identity['exe'].replace("'", "''")
    script = r"""$p = Get-CimInstance Win32_Process -Filter 'ProcessId=PID';
    if ($null -eq $p) { exit 0 }
    if ($p.Name -notmatch '^javaw?\.exe$' -or $p.CommandLine -notmatch 'PATTERN' -or
        $p.CreationDate.ToString('o') -ne 'CREATED' -or $p.ExecutablePath -ne 'EXE') { exit 4 }
    Stop-Process -Id PID -Force -ErrorAction Stop""".replace('PATTERN', pattern.replace("'", "''"))
    script = script.replace('CREATED', created).replace('EXE', exe).replace('PID', str(pid))
    subprocess.run(['powershell', '-NoProfile', '-Command', script], timeout=timeout, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def owned_for_diagnostic(target, started_wall, deadline, reserve):
    current = clients(budget(deadline, 3, reserve))
    if len(current) != 1 or not fresh_identity(current[0], started_wall) or identity(current[0]) != identity(target):
        raise RuntimeError('diagnostic client identity changed or ambiguous')
    sample = process_memory.sample([target['pid']])
    process_memory.require_accounting(sample)
    members = [p for p in sample['processes'] if p.get('pid') == target['pid'] and p.get('client') and p.get('inJob')]
    if len(members) != 1: raise RuntimeError('diagnostic client is not a verified job member')
    if process_memory.pressure(sample): raise RuntimeError('memory reserve reached; skip diagnostic')


def capture_jcmd(target, started_wall, evidence, deadline, phase, kind, reserve=20, jcmd=JCMD):
    commands = {'nmt': ['VM.native_memory', 'summary', 'scale=KB'], 'threads': ['Thread.print', '-l']}
    if kind not in commands or phase not in ('startup', 'late', 'precleanup', 'shutdown'):
        raise ValueError('unsupported diagnostic command or phase')
    result = {'phase': phase, 'kind': kind, 'status': 'UNVERIFIED', 'at': datetime.now(timezone.utc).isoformat(),
              'pid': target.get('pid'), 'created': target.get('created'), 'exe': target.get('exe')}
    proc = None
    try:
        if jcmd is None or not Path(jcmd).is_file(): raise RuntimeError('configured JDK 25 jcmd is unavailable; set JAVA25_HOME')
        owned_for_diagnostic(target, started_wall, deadline, reserve + 4)
        output = Path(evidence) / ('jcmd-' + phase + '-' + kind + '.txt')
        helper_env = dict(os.environ); helper_env.pop('JAVA_TOOL_OPTIONS', None)
        with output.open('xb') as stream:
            proc = subprocess.Popen([str(jcmd), str(int(target['pid'])), *commands[kind]], stdout=stream,
                                    stderr=subprocess.STDOUT, env=helper_env)
            try:
                proc.wait(timeout=budget(deadline, 3, reserve + 1))
            except subprocess.TimeoutExpired:
                proc.terminate()
                try: proc.wait(timeout=budget(deadline, 1, reserve))
                except subprocess.TimeoutExpired:
                    proc.kill(); proc.wait(timeout=budget(deadline, 1, reserve))
                result['reason'] = 'helper timeout'
                return result
        owned_for_diagnostic(target, started_wall, deadline, reserve)
        result['returncode'] = proc.returncode
        result['file'] = output.name
        text = output.read_text(encoding='utf-8', errors='replace')
        if proc.returncode != 0: result['reason'] = 'jcmd failed'
        elif kind == 'nmt' and ('Native Memory Tracking:' not in text or 'not enabled' in text.lower()):
            result['reason'] = 'NMT unavailable or not enabled'
        elif kind == 'threads' and 'java.lang.Thread.State:' not in text:
            result['reason'] = 'thread dump format unverified'
        else: result['status'] = 'observed'
    except Exception as exc:
        result['reason'] = str(exc)
    finally:
        if proc is not None and proc.poll() is None:
            proc.kill()  # only the helper this function started
            remaining = deadline - time.monotonic() - reserve
            if remaining > 0:
                try: proc.wait(timeout=min(1, remaining))
                except subprocess.TimeoutExpired: pass
    return result


def cleanup_clients(deadline, started_wall, seen, ack, env, result, diagnostic=None):
    # Continue observing late starts until the cleanup reserve. A newly created exact-instance
    # process is eligible only when its complete identity can be revalidated at termination.
    close_attempted = set()
    while time.monotonic() < deadline - 20:
        current = clients(budget(deadline, 5, 15))
        unknown = [p for p in current if not fresh_identity(p, started_wall)]
        if unknown: raise RuntimeError('ambiguous client ownership; refusing cleanup')
        for p in current: seen[identity(p)] = p
        if current:
            if len(current) != 1: raise RuntimeError('multiple clients; refusing cleanup')
            p = current[0]
            if identity(p) not in close_attempted:
                close_attempted.add(identity(p))
                close_env = env.copy()
                close_env['NIRNCRAFT_HARNESS_DEADLINE'] = str(deadline - 15)
                # Preserve a bounded drain window for generator/store shutdown before
                # forcing the exact owned client; retain the final cleanup/snapshot reserve.
                close_timeout = budget(deadline, 30, 20)
                close = subprocess.Popen([sys.executable, '-u', __file__, '--close-worker'], env=close_env)
                close_end = time.monotonic() + close_timeout
                try:
                    close.wait(timeout=min(close_timeout, 10) if diagnostic else close_timeout)
                except subprocess.TimeoutExpired:
                    if diagnostic and close.poll() is None and close_end - time.monotonic() > 5:
                        diagnostic(p, 'shutdown', 'threads', min(close_end, deadline - 20), 2)
                    try:
                        remaining = min(close_end - time.monotonic(), deadline - time.monotonic() - 20)
                        if remaining > 0: close.wait(timeout=remaining)
                    except subprocess.TimeoutExpired: pass
                    if close.poll() is None: stop_helper(close, deadline)
            current = clients(budget(deadline, 5, 15))
            if current:
                if len(current) != 1 or identity(current[0]) not in seen:
                    raise RuntimeError('client identity changed; refusing termination')
                stop_owned(current[0], budget(deadline, 5, 15))
                result['forced_cleanup'] = True
        # The launch handshake must be resolved; without it we continue watching the reserve.
        if launch_resolved(ack, seen, clients(budget(deadline, 5, 15))):
            break
        time.sleep(min(1, budget(deadline, 1, 20)))
    current = clients(budget(deadline, 5, 10))
    result['client_closed'] = not current
    result['launch_resolved'] = launch_resolved(ack, seen, current)
    return result['client_closed'] and result['launch_resolved']


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--limit', type=int, default=240, choices=range(90, 241), metavar='SECONDS')
    ap.add_argument('--memory-diagnostics', action='store_true',
                    help='sample MC VMA/JVM and native heap estimates, and save exact generator inputs; not a timing run')
    ap.add_argument('--jvm-native-diagnostics', action='store_true',
                    help='request JVM NMT summary and bounded owned-process diagnostics; not a performance run')
    ap.add_argument('--fresh-generator-cache', action='store_true',
                    help='use a new generator cache inside this run’s evidence directory')
    ap.add_argument('--transport', choices=('shared', 'host', 'gl'), default='shared',
                    help='shared S3 baseline or own-device S1 host-copy allocation control; not a speed comparison')
    ap.add_argument('--resource-reload', action='store_true', help='GL only: recreate models/context and compare the same camera before/after')
    ap.add_argument('--worker', nargs=2, metavar=('EVIDENCE', 'DEADLINE'), help=argparse.SUPPRESS)
    ap.add_argument('--close-worker', action='store_true', help=argparse.SUPPRESS)
    ap.add_argument('--snapshot-worker', metavar='EVIDENCE', help=argparse.SUPPRESS)
    args = ap.parse_args()
    if args.resource_reload and args.transport != 'gl': ap.error('--resource-reload requires --transport gl')
    if args.close_worker:
        game.close()
        return 0
    if args.snapshot_worker:
        snapshot(args.snapshot_worker)
        return 0
    if args.worker:
        return worker(args.worker[0], float(args.worker[1]))
    launch_environment = native_diagnostic_environment(os.environ, args.jvm_native_diagnostics)
    start = time.monotonic()
    started_wall = time.time()
    deadline = start + args.limit
    if game.lw.INSTANCE_NAME != 'NirnCraft-26.3-autotest':
        raise RuntimeError('refusing non-disposable instance')
    game.take_lease()
    safe_release = True  # no launch or option mutation until below
    try:
        if clients(budget(deadline, 5, 70)):
            raise RuntimeError('refusing to reuse or close an existing client')
        if launcher_processes(budget(deadline, 3, 70)):
            raise RuntimeError('refusing to launch with an existing Prism process')
        if args.jvm_native_diagnostics: reject_existing_native_tracking(game.lw.INSTANCE / 'instance.cfg')
        game.assert_prepared()
        player_files = atlas_tools.player_files(game)
        if not player_files:
            raise RuntimeError('kept world has no existing player')
        evidence = EVIDENCE_ROOT / datetime.now().strftime('live-%Y%m%d-%H%M%S-%f')
        evidence.mkdir(parents=True, exist_ok=False)
        baseline = memory_sample(evidence, phase='before-launch')
        require_live_memory_policy(baseline)
        record_cache_inventory(evidence, 'before')
        if args.transport == 'gl':
            if clients(budget(deadline, 5, 70)) or launcher_processes(budget(deadline, 3, 70)):
                raise RuntimeError('client or launcher appeared before pose update')
            prepare_gl_pose(player_files, evidence)
        options = game.GAME / 'options.txt'
        lines = options.read_bytes().splitlines(keepends=True)
        hits = [i for i, line in enumerate(lines) if line.startswith(b'preferredGraphicsBackend:')]
        if len(hits) != 1: raise RuntimeError('expected exactly one backend option line')
        prior = lines[hits[0]]
        ending = b'\r\n' if prior.endswith(b'\r\n') else b'\n' if prior.endswith(b'\n') else b''
        forced = (b'preferredGraphicsBackend:"opengl"' if args.transport == 'gl' else b'preferredGraphicsBackend:"vulkan"') + ending
        (evidence / 'options-backend-original.bin').write_bytes(prior)
        probe_flags.snapshot(game.lw.INSTANCE / 'instance.cfg', game.FLAGS_FILE, evidence)
        cancel = evidence / 'cancel-launch'
        ack = evidence / 'launch-ack.json'
        guard = LauncherGuard(ack, started_wall, Path(os.environ['LOCALAPPDATA']) / 'Programs/PrismLauncher/prismlauncher.exe')
        lines[hits[0]] = forced
        env = reload_environment(transport_environment(launch_environment, args.transport), args.transport, args.resource_reload)
        env['NIRNCRAFT_HARNESS_JVM'] += ',nirncraft.atlas.probeOwner=' + uuid.uuid4().hex
        env['NIRNCRAFT_HARNESS_NO_BUILD'] = '1'
        env = diagnostic_environment(env, evidence, args.memory_diagnostics)
        env, generator_cache = fresh_cache_environment(env, evidence, args.fresh_generator_cache)
        if generator_cache is not None: record_generator_cache_inventory(evidence, generator_cache, 'before')
        env['NIRNCRAFT_HARNESS_CANCEL_FILE'] = str(cancel)
        env['NIRNCRAFT_HARNESS_LAUNCH_ACK_FILE'] = str(ack)
        work_deadline = deadline - 60
        env['NIRNCRAFT_HARNESS_DEADLINE'] = str(work_deadline)
        result = {'state': 'UNVERIFIED', 'evidence': str(evidence), 'forced_cleanup': False,
                  'transport_requested': args.transport,
                  'requested_generator_cache_root': str(generator_cache) if generator_cache is not None else None,
                  'jvm_native_diagnostics_requested': args.jvm_native_diagnostics,
                  'jcmd_observations': [],
                  'memory_diagnostics_requested': args.memory_diagnostics}
        seen = {}
        sampled_phases = set()
        def diagnostic(target, phase, kind, end, reserve):
            observation = capture_jcmd(target, started_wall, evidence, end, phase, kind, reserve)
            result['jcmd_observations'].append(observation)
            return observation
        child = None
        safe_release = False
        options.write_bytes(b''.join(lines))
        try:
            child = subprocess.Popen([sys.executable, '-u', __file__, '--worker', str(evidence), str(work_deadline)], env=env)
            last = next_sample = 0
            while child.poll() is None and time.monotonic() < work_deadline:
                if guard.pinned is None and ack.exists(): guard.observe(work_deadline, 5)
                if time.monotonic() >= next_sample:
                    # Sampling Win32 counters directly avoids launching extra PowerShells.
                    # Identity discovery remains bounded and is needed only every five seconds.
                    memory = memory_sample(evidence, [p['pid'] for p in seen.values()])
                    process_memory.require_accounting(memory)
                    if process_memory.pressure(memory):
                        result['memory_pressure'] = True
                        raise RuntimeError('sampled job commit reached the 85% reserve; beginning cleanup')
                    for p in clients(budget(work_deadline, 5)):
                        if fresh_identity(p, started_wall): seen[identity(p)] = p
                    if args.jvm_native_diagnostics and len(seen) == 1:
                        target = next(iter(seen.values()))
                        phase = 'startup' if 'startup' not in sampled_phases else ('late' if time.monotonic() - start >= 100 and 'late' not in sampled_phases else None)
                        if phase is not None:
                            sampled_phases.add(phase)
                            diagnostic(target, phase, 'nmt', work_deadline, 8)
                    next_sample = time.monotonic() + 5
                if time.monotonic() - last >= 20:
                    print('TRACE supervisor elapsed', int(time.monotonic() - start), 'observed clients', len(seen), flush=True)
                    top = sorted(memory.get('processes', []), key=lambda p: p.get('privateBytes', 0), reverse=True)[:3]
                    print('TRACE memory', [(p['pid'], p.get('name'), round(p.get('privateBytes', 0) / 2**30, 3)) for p in top],
                          'job peak GiB', round((memory.get('job', {}).get('peakCommitBytes') or 0) / 2**30, 3), flush=True)
                    last = time.monotonic()
                time.sleep(min(1, budget(work_deadline, 1)))
            result['worker_timeout'] = child.poll() is None
        except Exception as exc:
            result['worker_error'] = str(exc)
        finally:
            # This must precede terminating any helper: the common launch guard observes it.
            try:
                cancel_and_stop(cancel, child, deadline)
            except Exception as exc: result['worker_stop_error'] = str(exc)
            if child:
                result['worker_rc'] = child.poll()
        try:
            if args.jvm_native_diagnostics and len(seen) == 1:
                diagnostic(next(iter(seen.values())), 'precleanup', 'nmt', deadline, 45)
            cleanup_clients(deadline, started_wall, seen, ack, env, result,
                            diagnostic if args.jvm_native_diagnostics else None)
            memory_sample(evidence, [p['pid'] for p in seen.values()], phase='after-client-cleanup')
            if result['client_closed'] and result['launch_resolved']:
                if generator_cache is not None: record_generator_cache_inventory(evidence, generator_cache, 'after')
                result['launcher_forced_cleanup'] = guard.finish(deadline)
                restore_after_launcher(guard, options, prior, forced, evidence, deadline, result)
                safe_release = True
                record_cache_inventory(evidence, 'after')
            else:
                result['restore_error'] = 'client closure or launch handoff unverified'
                if generator_cache is not None: result['generator_cache_after_unverified'] = True
        except Exception as exc:
            result['cleanup_error'] = str(exc)
        try:
            bounded_snapshot(evidence, deadline, result)
        except Exception as exc:
            result['snapshot_error'] = str(exc)
        result['elapsed_seconds'] = round(time.monotonic() - start, 2)
        (evidence / 'supervisor-receipt.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print('UNVERIFIED supervisor receipt', evidence / 'supervisor-receipt.json', flush=True)
        return 2
    finally:
        if safe_release: game.drop_lease()


if __name__ == '__main__':
    sys.exit(main())
