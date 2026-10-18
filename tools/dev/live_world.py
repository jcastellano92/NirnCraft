#!/usr/bin/env python3
"""Live packaged-world preview. Targets only Prism's disposable Skycraft-autotest instance.

See docs/LIVE-WORLD-PREVIEW.md. Run `status` before starting work; never overwrite a busy QA queue.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid
import zipfile
import zlib

REPO = Path(__file__).resolve().parents[2]
INSTANCE = Path(os.environ.get("APPDATA", "")) / "PrismLauncher/instances/Skycraft-autotest"
GAME = INSTANCE / ".minecraft"
DEV = GAME / "world-preview"
GROUPS = {
    "scenes": ["skycraft/scatter"],
    "places": ["skycraft/tamriel/places"],
    "cityplans": ["skycraft/tamriel/cityplans", "skycraft/tamriel/styles"],
    "quests": ["skycraft/quests"],
    "palettes": ["skycraft/tamriel/palettes.json"],
}


def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex[:8])
    tmp.write_bytes(data)
    os.replace(tmp, path)


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return {}


def processes():
    # Never print command lines (they contain launcher authentication). Select exactly this instance.
    script = r"""$p = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match '[/\\]instances[/\\]Skycraft-autotest[/\\]'
    } | ForEach-Object {
        $g = Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue
        [pscustomobject]@{pid=$_.ProcessId; window=$g.MainWindowTitle; windowOpen=($g.MainWindowHandle -ne 0); responding=$g.Responding}
    }); ConvertTo-Json -InputObject $p -Compress"""
    result = subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True, check=True)
    return json.loads(result.stdout or "[]")


def status():
    procs = processes()
    live = read_json(GAME / "skycraft-live-status.json")
    age = (time.time() * 1000 - live.get("updated", 0)) / 1000
    live["ageSeconds"] = round(age, 1) if live else None
    live["fresh"] = age < 15 and any(p["pid"] == live.get("pid") for p in procs)
    heartbeat = DEV / "outbox/heartbeat"
    ready = DEV / "outbox/ready"
    lines = ready.read_text().splitlines() if ready.exists() else []
    ready_pid = int(lines[0]) if lines and lines[0].isdigit() else None
    server_fresh = heartbeat.exists() and time.time() - heartbeat.stat().st_mtime < 15
    return {"instance": str(INSTANCE), "running": bool(procs), "processes": procs,
            "client": live, "serverReady": server_fresh and any(p["pid"] == ready_pid for p in procs),
            "serverHeartbeat": heartbeat.read_text().strip() if heartbeat.exists() else "",
            "inbox": sorted(p.name for p in (DEV / "inbox").glob("*.cmd"))}


def storage():
    script = r"""$rows = @(Get-Partition | Where-Object DriveLetter | ForEach-Object {
        $part = $_; $disk = Get-Disk -Number $part.DiskNumber; $vol = Get-Volume -DriveLetter $part.DriveLetter
        [pscustomobject]@{drive=[string]$part.DriveLetter; model=$disk.FriendlyName; bus=[string]$disk.BusType;
            freeGB=[math]::Round($vol.SizeRemaining/1GB,1); sizeGB=[math]::Round($vol.Size/1GB,1)}
    }); ConvertTo-Json -InputObject $rows -Compress"""
    result = subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True, check=True)
    rows = json.loads(result.stdout)
    return {"repository": str(REPO), "previewGame": str(GAME), "volumes": rows,
            "nvmeDetected": any(r["bus"] == "NVMe" for r in rows),
            "guidance": "Prefer SSD/NVMe for active build outputs, saves and LOD caches when disk-bound. "
                        "Benchmark separately from CPU generation and shader compilation. No files were moved."}


def profile():
    log = (GAME / "logs/latest.log").read_text(encoding="utf-8", errors="replace")
    def number(pattern):
        found = re.findall(pattern, log)
        return float(found[-1]) if found else None
    return {
        "launchToWorldSeconds": number(r"Total time to load game and open world was ([\d.]+) seconds"),
        "menuToWorldSeconds": number(r"Time from main menu to in-game was ([\d.]+) seconds"),
        "staleBakes": sorted(set(re.findall(r"\[bake\] (\w+) is stale", log))),
        "loadedBakes": sorted(set(re.findall(r"\[bake\] loaded (\w+)", log))),
        "regenerations": re.findall(r"\[dev\] (Regen done:[^\r\n]+)", log),
        "note": "Single-run diagnostics; not a controlled hardware benchmark. "
                "Readiness checks native terrain/media; optional sampled DH data still does not prove all LOD GPU meshes or exposure convergence.",
    }


def require_ready():
    state = status()
    if not state["serverReady"]:
        raise RuntimeError("No live world-preview heartbeat. Use status; enable preview flags and launch/load the world first.")
    return state


def submit(lines, timeout=900):
    require_ready()
    job = str(time.time_ns()) + "-" + uuid.uuid4().hex[:6]
    atomic(DEV / "inbox" / (job + ".cmd"), ("\n".join(lines) + "\n").encode())
    print("Queued", job, flush=True)
    done, output = DEV / "outbox" / (job + ".done"), DEV / "outbox" / (job + ".out")
    deadline, pos = time.monotonic() + timeout, 0
    while time.monotonic() < deadline:
        if output.exists():
            text = output.read_text(encoding="utf-8", errors="replace")
            print(text[pos:], end="", flush=True)
            pos = len(text)
        if done.exists():
            result = done.read_text()
            print(result, flush=True)
            if "status=OK" not in result:
                raise RuntimeError("Preview job failed; see " + str(output))
            return job
        time.sleep(0.5)
    raise RuntimeError("Job is still pending: " + job + "; inspect status/outbox before queuing more work")


def live_actions(lines, timeout=180, cancel=False):
    state = status()
    c = state["client"]
    if not c.get("fresh"):
        raise RuntimeError("Client control heartbeat is absent/stale; use status and wait for the world")
    if not cancel and any(c.get(k, 0) for k in ("queued", "waitTicks", "burstFrames", "settling", "commandPending")):
        raise RuntimeError("QA control queue is busy; wait for it to finish")
    atomic(GAME / "skycraft-live.txt", ("\n".join(lines) + "\n").encode())
    stamp = (GAME / "skycraft-live.txt").stat().st_mtime_ns // 1_000_000
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if cancel and not processes():
            return
        c = read_json(GAME / "skycraft-live-status.json")
        if c.get("controlVersion", 0) == stamp and not any(c.get(k, 0) for k in ("queued", "waitTicks", "burstFrames", "settling", "commandPending")):
            if c.get("error"):
                raise RuntimeError(c["error"])
            return
        time.sleep(0.5)
    raise RuntimeError("Client did not acknowledge completion; inspect status/log before retrying")


def snapshot(target):
    """Stage complete immutable files before switching the pointer; retain unrelated groups."""
    root = DEV / "resources"
    old = read_json(root / "current.json")
    ready = DEV / "outbox/ready"
    boot = ready.read_text() if ready.exists() else ""
    name = str(time.time_ns())
    dest = root / name
    files = {}
    if old and old.get("boot", "") == boot:
        previous = root / old["snapshot"]
        for rel, digest in old["files"].items():
            file = previous / rel.lstrip("/")
            raw = file.read_bytes()
            if hashlib.sha256(raw).hexdigest() != digest:
                raise RuntimeError("Previous snapshot was edited: " + rel)
            out = dest / rel.lstrip("/")
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(raw)
            files[rel] = digest
    groups = list(GROUPS) if target == "all" else [target]
    prefixes = [p for group in groups for p in GROUPS[group]]
    for rel in list(files):
        if any(rel == "/" + p or rel.startswith("/" + p + "/") for p in prefixes):
            del files[rel]  # removals fall back to the packaged baseline
    source = REPO / "mod/src/main/resources"
    for prefix in prefixes:
        path = source / prefix
        paths = sorted(path.rglob("*.json")) if path.is_dir() else [path]
        for file in paths:
            raw = file.read_bytes()
            json.loads(raw.decode("utf-8-sig"))
            rel = file.relative_to(source).as_posix()
            out = dest / rel
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(raw)
            files["/" + rel] = hashlib.sha256(raw).hexdigest()
    atomic(root / "current.json", json.dumps({"snapshot": name, "boot": boot, "files": files}, indent=2).encode())
    return name, len(files)


def decode_bake(raw):
    if len(raw) < 2:
        raise ValueError("Truncated bake header")
    n = int.from_bytes(raw[:2], "big")
    digest = raw[2:2+n].decode("ascii")
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("Invalid bake input hash")
    payload = zlib.decompress(raw[2+n:])
    if not payload:
        raise ValueError("Empty bake payload")
    return digest, hashlib.sha256(payload).hexdigest()


def validate_bakes(folder, expected):
    manifest = json.loads((folder / "manifest.json").read_text())
    names = {name + ".bin" for name in ("landmass", "roadplan", "streetplan", "sceneplan")}
    if manifest.get("format") != 1 or set(manifest.get("files", [])) != names:
        raise ValueError("Expected a complete format-1 bake bundle (all four plans)")
    if manifest.get("inputs") != expected:
        raise ValueError("Bake inputs differ from the packaged runtime; rebuild/install matching code and data first")
    result = {}
    for name in sorted(names):
        digest, payload = decode_bake((folder / name).read_bytes())
        if digest != expected:
            raise ValueError("Mixed/stale input hash: " + name)
        result[name] = payload
    return result


def upload_bakes(folder):
    if processes():
        raise RuntimeError("Close the preview first: bakes replace packaged plans and take effect at next launch")
    stamp = DEV / "outbox/bake-packaged-inputs"
    expected = stamp.read_text().strip()
    core = GAME / "mods/skycraft-core.jar"
    receipt = read_json(DEV / "outbox/runtime.json")
    if receipt.get("coreSha256") != hashlib.sha256(core.read_bytes()).hexdigest():
        raise RuntimeError("Runtime receipt does not match this jar; launch the preview once to record its packaged inputs")
    result = validate_bakes(folder, expected)
    backup = DEV / "bakes" / str(time.time_ns())
    backup.mkdir(parents=True)
    shutil.copy2(core, backup / core.name)
    for name in [*result, "manifest.json"]:
        shutil.copy2(folder / name, backup / name)
    tmp = core.with_suffix(".preview-tmp")
    replacements = {"skycraft/baked/" + n: (folder / n).read_bytes() for n in [*result, "manifest.json"]}
    with zipfile.ZipFile(core) as src, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
        for entry in src.infolist():
            if entry.filename not in replacements:
                dst.writestr(entry, src.read(entry))
        for name, raw in replacements.items():
            dst.writestr(name, raw)
    os.replace(tmp, core)
    print(json.dumps({"installed": result, "backup": str(backup), "restartRequired": True}, indent=2))


def jar_bake_inputs(archive):
    """Mirror Bake.inputsHash's declared dependency bytes, without booting Java.

    Callers also require an unchanged Bake.class and equality with a hash actually
    recorded by the running JVM. A parser mismatch therefore refuses reuse.
    """
    source=(REPO/'mod/src/main/java/com/skycraft/bake/Bake.java').read_text()
    source=re.sub(r'/\*.*?\*/|//[^\n]*','',source,flags=re.S)
    def array(name):
        match=re.search(r'\b'+name+r'\s*=\s*\{(.*?)\};',source,re.S)
        if not match: raise ValueError('Missing bake dependency declaration: '+name)
        return re.findall(r'"([^"]+)"',match[1])
    names=set(archive.namelist());h=hashlib.sha256()
    def feed(name):
        h.update(('/'+name).encode())
        if name in names:h.update(archive.read(name))
    version=re.search(r'\bFORMAT\s*=\s*(\d+)',source)
    if not version:raise ValueError('Missing bake format')
    h.update(('format'+version[1]).encode())
    for name in array('MAP_FILES'):feed('skycraft/tamriel/'+name)
    index='skycraft/tamriel/places/index.json'
    if index in names:
        raw=archive.read(index);h.update(raw)
        for name in json.loads(raw):feed('skycraft/tamriel/places/'+name)
    for name in array('DATA_FILES'):feed(name.lstrip('/'))
    index='skycraft/tamriel/cityplans/index.json'
    if index in names:
        raw=archive.read(index);h.update(raw)
        for name in json.loads(raw).get('plans',[]):feed('skycraft/tamriel/cityplans/'+name+'.json')
    for name in array('CLASSES'):feed(name.replace('.','/')+'.class')
    return h.hexdigest()


def install_core(jar):
    """Preserve runtime plans only when every changed entry is in known preview/render paths."""
    if processes():
        raise RuntimeError('Close the disposable preview before installing code')
    core = GAME / 'mods/skycraft-core.jar'
    receipt = read_json(DEV / 'outbox/runtime.json')
    known = receipt.get('coreSha256') == hashlib.sha256(core.read_bytes()).hexdigest()
    with zipfile.ZipFile(core) as old, zipfile.ZipFile(jar) as new:
        names = set(old.namelist()) | set(new.namelist())
        changed = [n for n in sorted(names) if n not in old.namelist() or n not in new.namelist()
                   or old.read(n) != new.read(n)]
        safe = known and all(n.startswith(('com/skycraft/client/autotest/', 'com/skycraft/fire/client/',
                                          'com/skycraft/atmosphere/client/', 'assets/skycraft/shaders/', 'skycraft/baked/'))
                             or n in ('skycraft/generation-identity.txt','assets/skycraft/textures/environment/smoke_sources.png')
                             or any(n==p+'.class' or n.startswith(p+'$') for p in
                                    ('com/skycraft/inventory/PackSelfTest','com/skycraft/inventory/OverflowSelfTest')) for n in changed)
        expected=(DEV/'outbox/bake-packaged-inputs').read_text().strip() if known else ''
        bake_class='com/skycraft/bake/Bake.class'
        if known and bake_class in old.namelist() and bake_class in new.namelist() and old.read(bake_class)==new.read(bake_class):
            # QA/server ticket changes need not trigger an expensive rebake when
            # every declared bake dependency still has the exact runtime hash.
            safe=jar_bake_inputs(old)==expected and jar_bake_inputs(new)==expected
        replacements = {}
        if safe:
            manifest = json.loads(old.read('skycraft/baked/manifest.json'))
            expected = (DEV / 'outbox/bake-packaged-inputs').read_text().strip()
            if manifest.get('inputs') != expected:
                raise ValueError('Existing packaged bake has not been verified for the running inputs')
            for n in manifest['files']:
                raw = old.read('skycraft/baked/' + n)
                if decode_bake(raw)[0] != expected:
                    raise ValueError('Existing packaged bake contains mixed inputs')
                replacements['skycraft/baked/' + n] = raw
            replacements['skycraft/baked/manifest.json'] = old.read('skycraft/baked/manifest.json')
        backup = DEV / 'core-backups' / str(time.time_ns()) / 'skycraft-core.jar'
        backup.parent.mkdir(parents=True)
        shutil.copy2(core, backup)
        tmp = core.with_suffix('.preview-tmp')
        with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as target:
            for entry in new.infolist():
                target.writestr(entry, replacements.get(entry.filename, new.read(entry)))
    os.replace(tmp, core)
    print(json.dumps({'installed': str(jar), 'backup': str(backup), 'changedEntries': changed,
                      'verifiedRuntimeBakesPreserved': safe, 'restartRequired': True}, indent=2))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="action", required=True)
    for cmd in ("status", "storage", "profile", "enable", "launch", "close", "shader"):
        sub.add_parser(cmd)
    command = sub.add_parser("commands")
    command.add_argument("file", type=Path)
    actions = sub.add_parser("actions")
    actions.add_argument("file", type=Path)
    capture = sub.add_parser("capture")
    capture.add_argument("tag")
    reload = sub.add_parser("reload")
    reload.add_argument("target", choices=[*GROUPS, "all"])
    regen = sub.add_parser("regen")
    regen.add_argument("bounds", nargs=4, type=int, metavar=("x1", "z1", "x2", "z2"))
    bake = sub.add_parser("upload-bakes")
    bake.add_argument("folder", type=Path)
    install = sub.add_parser('install-core')
    install.add_argument('jar', type=Path)
    args = ap.parse_args()
    if args.action == 'install-core':
        install_core(args.jar)
    elif args.action == "profile":
        print(json.dumps(profile(), indent=2))
    elif args.action == "storage":
        print(json.dumps(storage(), indent=2))
    elif args.action == "status":
        state = status()
        print(json.dumps(state, indent=2))
    elif args.action == "enable":
        if processes():
            raise RuntimeError("Client is running; close it before changing its launch flags")
        cfg = INSTANCE / "instance.cfg"
        text = cfg.read_text()
        flags = "-Dskycraft.dev=world-preview -Dskycraft.preview.resources=world-preview/resources"
        if not re.search(r'^JvmArgs=', text, re.M):
            raise RuntimeError("Instance has no JvmArgs entry; prepare the disposable autotest instance first")
        if "-Dskycraft.dev=" in text and any(flag not in text for flag in flags.split()):
            raise RuntimeError("Instance has different preview paths; review its launch flags before enabling")
        if "-Dskycraft.dev=" not in text:
            text = re.sub(r'^JvmArgs="?(.*?)"?$', lambda m: 'JvmArgs="' + m[1].strip('"') + ' ' + flags + '"', text, flags=re.M)
        atomic(cfg, text.encode())
        print("Preview flags enabled. No normal Prism instance changed.")
    elif args.action == "launch":
        if processes():
            print("Preview already running; use status")
            return
        if not INSTANCE.is_dir():
            raise RuntimeError("Prepare the disposable instance first with tools/qa/prism_autotest.ps1")
        atomic(GAME / "skycraft-live.txt", b"# New preview session; idle\n")
        launcher = Path(os.environ["LOCALAPPDATA"]) / "Programs/PrismLauncher/prismlauncher.exe"
        subprocess.Popen([str(launcher), "--launch", "Skycraft-autotest"], creationflags=subprocess.CREATE_NO_WINDOW)
        print("Preview launch requested; status will report when the world heartbeat is ready")
    elif args.action == "close":
        if not processes():
            print("Preview already closed")
            return
        if status()["client"].get("fresh"):
            live_actions(["stop"], timeout=180, cancel=True)
        else:
            # Bootstrap from the previous live controller, which had no status JSON.
            log = GAME / "logs/latest.log"
            if not log.exists() or "Autotest live: ready" not in log.read_text(encoding="utf-8", errors="replace"):
                raise RuntimeError("No live controller confirmed; close the preview through its game window")
            atomic(GAME / "skycraft-live.txt", b"stop\n")
        deadline = time.monotonic() + 180
        while processes() and time.monotonic() < deadline:
            time.sleep(1)
        if processes():
            raise RuntimeError("Clean close is still pending; no process was killed")
        print("Preview closed cleanly")
    elif args.action == "commands":
        submit(args.file.read_text(encoding="utf-8-sig").splitlines())
    elif args.action == "actions":
        live_actions(args.file.read_text(encoding="utf-8-sig").splitlines(), timeout=900)
        print("Client sequence acknowledged")
    elif args.action == "capture":
        live_actions(["settle 1800", "capture " + args.tag])
        print("Capture requested; check build/autotest-screenshots after encoding finishes")
    elif args.action == "reload":
        state = require_ready()
        if state["inbox"] or state["serverHeartbeat"].split()[2] != "-":
            raise RuntimeError("Server job queue is busy; wait before activating another resource snapshot")
        if any(state["client"].get(k, 0) for k in ("queued", "waitTicks", "burstFrames", "settling", "commandPending")):
            raise RuntimeError("Client capture sequence is busy; wait before changing world data")
        name, count = snapshot(args.target)
        print(f"Staged snapshot {name}: {count} JSON files")
        submit(["skycraft-dev reload " + args.target, "skycraft-dev bake-status"])
    elif args.action == "regen":
        a, b, c, d = args.bounds
        x1, x2, z1, z2 = min(a, c), max(a, c), min(b, d), max(b, d)
        chunks = (x2 // 16 - x1 // 16 + 1) * (z2 // 16 - z1 // 16 + 1)
        if chunks > 256:
            raise ValueError("Preview regeneration is limited to 256 chunks; split the area")
        # Existing regen needs all players and their chunk tickets far from the area.
        submit(["@timeout 900", f"tp @a {x2+2048} 360 {z2+2048}",
                f"skycraft-dev regen {x1} {z1} {x2} {z2}", "skycraft-dev status"])
        print("Fresh area ready; use a dungeon view/camera command to return. Release tickets when done.")
    elif args.action == "shader":
        require_ready()
        sys.path.insert(0, str(REPO / "tools/shaders"))
        import patch_complementary_clouds
        import patch_sun_horizon
        shader = GAME / "shaderpacks/ComplementaryReimagined_r5.9.3.zip"
        log = GAME / "logs/latest.log"
        log_offset = log.stat().st_size
        state = status()["client"]
        if any(state.get(k, 0) for k in ("queued", "waitTicks", "burstFrames", "settling", "commandPending")):
            raise RuntimeError("QA capture queue is busy; wait before updating shaders")
        # Rebuild from the same pinned original as release packaging. Editing a
        # repeatedly patched zip can retain abandoned experiment files/uniforms.
        original=next((REPO/'dist/.cache').glob('*ComplementaryReimagined_r5.9.3.zip'),None)
        if original is None: raise RuntimeError('Pinned original shader cache missing; run the instance builder first')
        temporary=shader.with_name(shader.stem+'.preview-build.zip')
        shutil.copy2(original,temporary)
        patch_complementary_clouds.patch_shaderpack_zip(temporary)
        patch_sun_horizon.patch_zip(temporary,temporary)
        def contents(path):
            with zipfile.ZipFile(path) as pack:return {n:hashlib.sha256(pack.read(n)).hexdigest() for n in pack.namelist()}
        if contents(shader)==contents(temporary):
            temporary.unlink()
            print('Shader already matches source; no reload needed')
            return
        backup=DEV/'shader-backups'/(str(time.time_ns())+'.zip');backup.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(shader,backup)
        try:os.replace(temporary,shader)
        except PermissionError:
            # Windows Oculus keeps the ZIP open without delete sharing. Its
            # existing live patch path permits writing; reload follows immediately.
            shutil.copyfile(temporary,shader);temporary.unlink()
        with zipfile.ZipFile(shader) as pack:
            if pack.testzip() is not None:raise RuntimeError('Rebuilt shader archive failed CRC validation')
        live_actions(["reload"])
        with log.open("rb") as handle:
            handle.seek(log_offset)
            emitted = handle.read().decode("utf-8", errors="replace")
        if re.search(r"error:|ShaderCompileException|Failed to compile|Invalid program|Error compiling", emitted, re.I):
            raise RuntimeError("Shader reload logged a compilation error; inspect logs/latest.log")
        print("Shader edits applied and reload acknowledged by the running client")
    elif args.action == "upload-bakes":
        upload_bakes(args.folder)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, OSError, KeyError, zipfile.BadZipFile, zlib.error) as exc:
        print("ERROR:", exc, file=sys.stderr)
        sys.exit(1)
