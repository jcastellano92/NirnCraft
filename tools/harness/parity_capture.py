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


_PARITY_JVM_OVERRIDE = re.compile(r'^-Dnirncraft\.atlas\.(?:cacheDir|memoryDiagnostics)=')
_STRAY_HARNESS_FLAG = re.compile(r'^-Dnirncraft\.(?:atlas|autotest)\.')
_PARITY_PROP_LINE = re.compile(rb'^nirncraft\.atlas\.(?:cacheDir|memoryDiagnostics)=')


def sanitize_instance_cfg(raw):
    """Strip duplicate -Dnirncraft.(atlas|autotest).* tokens outside the harness marker and parity-only overrides inside it."""
    text = raw.decode('utf-8')
    m = re.search(r'^(JvmArgs="?)([^\r\n"]*?)("?)(\r?)$', text, re.M)
    if not m:
        return raw
    prefix, body, suffix, cr = m.groups()
    block_re = re.compile(re.escape(game.MARK[0]) + r'.*?' + re.escape(game.MARK[1]))
    block_match = block_re.search(body)
    outside = block_re.sub('', body).split()
    clean_outside = [tok for tok in outside if not _STRAY_HARNESS_FLAG.match(tok)]
    if block_match:
        inner = block_match.group(0)[len(game.MARK[0]):-len(game.MARK[1])].split()
        seen = set()
        clean_inner = []
        for tok in inner:
            if _PARITY_JVM_OVERRIDE.match(tok) or tok in seen:
                continue
            seen.add(tok)
            clean_inner.append(tok)
        if clean_inner:
            clean_outside.append(f"{game.MARK[0]} {' '.join(clean_inner)} {game.MARK[1]}")
    rebuilt = f"{prefix}{' '.join(clean_outside)}{suffix}{cr}"
    return (text[:m.start()] + rebuilt + text[m.end():]).encode('utf-8')


def sanitize_flags_properties(raw):
    """Remove parity_capture's temporary cacheDir / memoryDiagnostics overrides from nirncraft-harness-flags.properties."""
    if raw is None:
        return None
    kept = [line for line in raw.splitlines(keepends=True) if not _PARITY_PROP_LINE.match(line.lstrip())]
    return b''.join(kept)


def snapshot_launcher_state(instance_dir=None, flags_file=None, atomic=None):
    cfg_path = Path(instance_dir or game.lw.INSTANCE) / 'instance.cfg'
    flags_path = Path(flags_file or game.FLAGS_FILE)
    write_atomic = atomic or game.lw.atomic
    clean_cfg = sanitize_instance_cfg(cfg_path.read_bytes()) if cfg_path.exists() else None
    if clean_cfg is not None:
        write_atomic(cfg_path, clean_cfg)
    clean_flags = sanitize_flags_properties(flags_path.read_bytes()) if flags_path.exists() else None
    if clean_flags is not None:
        write_atomic(flags_path, clean_flags)
    return {'cfg_path': cfg_path, 'cfg': clean_cfg, 'flags_path': flags_path, 'flags': clean_flags, 'atomic': write_atomic}


def restore_launcher_state(saved):
    if not saved:
        return
    write_atomic = saved['atomic']
    if saved['cfg'] is not None:
        write_atomic(saved['cfg_path'], saved['cfg'])
    if saved['flags'] is None:
        saved['flags_path'].unlink(missing_ok=True)
    else:
        write_atomic(saved['flags_path'], saved['flags'])

JCMD = Path(os.environ.get('JAVA25_HOME') or r'E:\NirnCraft-scratch\toolchains\jdk-25.0.4.1+1') / 'bin/jcmd.exe'


def client_pid():
    """The harness-launched client's java pid (its command line carries the nirncraft.atlas flags)."""
    import subprocess
    r = subprocess.run(['powershell', '-NoProfile', '-Command',
                        "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -match 'nirncraft' -and $_.CommandLine -notmatch 'gradle' } | Select-Object -First 1 -ExpandProperty ProcessId"],
                       capture_output=True, text=True)
    return int(r.stdout.strip()) if r.stdout.strip().isdigit() else None


def close_with_dump(out, mode, after=40):
    """game.close() with thread dumps of the client if it has not exited `after` seconds in (a hung shutdown)."""
    import subprocess, threading
    pid = client_pid()
    box = {}
    def run():
        try: game.close()
        except Exception as exc: box['error'] = str(exc)
    t = threading.Thread(target=run); t.start()
    start = time.monotonic(); dumps = 0
    while t.is_alive():
        if pid and dumps < 3 and time.monotonic() - start > after * (dumps + 1):
            r = subprocess.run([str(JCMD), str(pid), 'Thread.print', '-l'], capture_output=True, text=True, timeout=60)
            (out / f'{mode}-shutdown-hang-{dumps}.txt').write_text(r.stdout + r.stderr, encoding='utf-8')
            r2 = subprocess.run(['powershell', '-NoProfile', '-Command', "(Get-Process -Id %d).Threads | Sort-Object TotalProcessorTime -Descending | Select-Object Id,ThreadState,WaitReason,TotalProcessorTime,PriorityLevel | Format-Table -AutoSize | Out-String -Width 200" % pid], capture_output=True, text=True, timeout=60)
            (out / f'{mode}-shutdown-hang-{dumps}-os-threads.txt').write_text(r2.stdout + r2.stderr, encoding='utf-8')
            busy = [ln.split()[0] for ln in r2.stdout.splitlines() if re.match(r'\s*\d+\s+Running\b', ln)]
            top = [ln.split()[0] for ln in r2.stdout.splitlines()[3:6] if ln.strip()]
            stacks = []
            for tid in list(dict.fromkeys(busy + top))[:6]:
                r3 = subprocess.run([sys.executable, str(HERE / 'native_stack.py'), str(pid), tid, '16384'], capture_output=True, text=True, timeout=60)
                stacks.append(r3.stdout + r3.stderr)
            (out / f'{mode}-shutdown-hang-{dumps}-native-stacks.txt').write_text('\n'.join(stacks), encoding='utf-8')
            print('TRACE shutdown hang: thread dump', dumps, 'pid', pid, flush=True)
            dumps += 1
        time.sleep(2)
    summary_close = {'pid': pid, 'seconds': round(time.monotonic() - start, 1), 'thread_dumps': dumps, **box}
    (out / f'{mode}-close.json').write_text(json.dumps(summary_close, indent=2), encoding='utf-8')
    if box: raise RuntimeError('clean close failed: ' + box['error'])

def enable_validation(out):
    """Khronos validation (object lifetime included) for the launched client: layer env plus a settings file in `out`."""
    sdk = Path(os.environ.get('NIRNCRAFT_TOOLCHAINS') or r'E:\Skycraft-scratch\toolchains') / 'VulkanSDK/1.4.357.0/Bin'
    if not (sdk / 'VkLayer_khronos_validation.json').exists(): raise SystemExit('validation layer not found in ' + str(sdk))
    log = (out / 'validation.log').resolve()
    log.unlink(missing_ok=True)
    settings = out / 'vk_layer_settings.txt'
    settings.write_text('khronos_validation.debug_action = VK_DBG_LAYER_ACTION_LOG_MSG\n'
                        'khronos_validation.log_filename = %s\n'
                        'khronos_validation.report_flags = error,warn,info\n'
                        'khronos_validation.duplicate_message_limit = 10000\n'
                        'khronos_validation.message_format_display_application_name = true\n' % log.as_posix(), encoding='utf-8')
    os.environ['VK_LAYER_SETTINGS_PATH'] = str(out.resolve())
    os.environ['VK_ADD_LAYER_PATH'] = str(sdk)
    os.environ['VK_INSTANCE_LAYERS'] = 'VK_LAYER_KHRONOS_validation'


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
    if '--memdiag' in sys.argv:
        os.environ['NIRNCRAFT_HARNESS_JVM'] = ','.join(filter(None, [os.environ.get('NIRNCRAFT_HARNESS_JVM', ''), 'nirncraft.atlas.memoryDiagnostics=true']))
    if '--validation' in sys.argv:
        enable_validation(out)
    if game.running(): game.close()
    launcher_state = snapshot_launcher_state()
    if '--cold' in sys.argv:  # a private empty cache dir (never wipes the shared test-instance cache): the bake is still running at shutdown
        root = (out / 'generated-cache').resolve(); root.mkdir(exist_ok=True)
        (root / 'keep-gen-sets').write_text('3\n', encoding='utf-8')
        os.environ['NIRNCRAFT_HARNESS_JVM'] = ','.join(filter(None, [os.environ.get('NIRNCRAFT_HARNESS_JVM', ''), 'nirncraft.atlas.cacheDir=' + root.as_posix()]))
        print('TRACE cold: private cache', root, flush=True)
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
        for name, turn in POSES[:int(sys.argv[sys.argv.index('--poses') + 1]) if '--poses' in sys.argv else len(POSES)]:
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
            if game.running(): close_with_dump(out, mode)
        finally:
            game.lw.atomic(game.GAME / 'options.txt', original)
            restore_launcher_state(launcher_state)


if __name__ == '__main__':
    main()
