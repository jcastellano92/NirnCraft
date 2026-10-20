#!/usr/bin/env python3
"""Live packaged-world preview. Targets only explicitly allowlisted disposable native instances.

See docs/LIVE-WORLD-PREVIEW.md. Run `status` before starting work; never overwrite a busy QA queue.
"""
import argparse
from contextlib import nullcontext
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
sys.path.insert(0, str(REPO / "tools"))
from validate_pack import TARGET, validate_core_jar
from dev.process_guard import process_guard
PRISM_ROOT = Path(os.environ.get("NIRNCRAFT_PRISM_ROOT", str(Path(os.environ.get("APPDATA", "")) / "PrismLauncher"))).resolve()
DIAGNOSTIC_NAME = "NirnCraft-26.3-core-diagnostic"


def selected_instance(name):
    if name not in (TARGET["instanceName"], DIAGNOSTIC_NAME):
        raise ValueError("Only the exact native preview or core diagnostic instance can be controlled")
    return name


INSTANCE_NAME = selected_instance(os.environ.get("NIRNCRAFT_NATIVE_INSTANCE", TARGET["instanceName"]))
INSTANCE = PRISM_ROOT / "instances" / INSTANCE_NAME
GAME = INSTANCE / ".minecraft"
HEARTBEAT_STALE = 30  # seconds without a status write: the client froze, crashed or lost its control loop
STALL_S = 150  # seconds without any progress (action, queue, export, settle streaks) while busy: a hang, fail it now
DEV = GAME / "world-preview"
GROUPS = {
    "scenes": ["nirncraft/scatter"],
    "places": ["nirncraft/tamriel/places"],
    "cityplans": ["nirncraft/tamriel/cityplans", "nirncraft/tamriel/styles"],
    "quests": ["nirncraft/quests"],
    "palettes": ["nirncraft/tamriel/palettes.json"],
}


def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex[:8])
    tmp.write_bytes(data)
    os.replace(tmp, path)


def heartbeat_age(status, status_file, now=None):
    """Seconds since the client last wrote its status. A torn or empty read (the client rewrites the file every
    second) has no `updated`: then the file's own time decides; a missing field read as 0 made a heartbeat 56 years
    old and closed healthy clients as "crashed" (2026-10-19). audits/runner_selftest.py holds it to that."""
    now = time.time() if now is None else now
    updated = (status or {}).get("updated", 0) / 1000
    if not updated:
        try:
            updated = Path(status_file).stat().st_mtime
        except OSError:
            updated = now
    return now - updated


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return {}


def validate_components(components):
    """Prism adds the official LWJGL dependency after resolving exact26.3 metadata."""
    expected = {"net.minecraft": TARGET["minecraft"], "net.neoforged": TARGET["neoforge"]}
    actual = {row["uid"]: row["version"] for row in components}
    if len(actual) != len(components) or any(actual.get(uid) != version for uid, version in expected.items()):
        raise ValueError("Preview platform is not exactly Minecraft26.3 / the pinned NeoForge")
    for row in components:
        if row['uid'] not in expected and not (row['uid'] == 'org.lwjgl3' and row['version'] == '3.4.3' and row.get('dependencyOnly') is True):
            raise ValueError("Unexpected preview platform component: " + row['uid'])


def validate_diagnostic_instance(instance):
    """Core diagnostics have a separate receipt and can never certify the full pack."""
    game = instance / ".minecraft"
    marker = read_json(game / "migration/core-diagnostic.json")
    expected = {key: TARGET[key] for key in ("minecraft", "neoforge", "java")}
    if instance.name != DIAGNOSTIC_NAME or marker.get("instanceName") != DIAGNOSTIC_NAME or \
            marker.get("diagnosticOnly") is not True or marker.get("fullPackAcceptance") is not False or \
            marker.get("target") != expected:
        raise ValueError("Missing or mismatched native core diagnostic identity")
    if {path.name for path in (game / "mods").iterdir()} != {"nirncraft-core.jar"}:
        raise ValueError("Core diagnostic must contain only the actual native NirnCraft core")
    core = game / "mods/nirncraft-core.jar"
    validate_core_jar(core)
    if hashlib.sha256(core.read_bytes()).hexdigest() != marker.get("coreSha256"):
        raise ValueError("Core diagnostic changed since preparation; rebuild its separate diagnostic archive")
    if (game / "shaderpacks").exists() and any((game / "shaderpacks").iterdir()):
        raise ValueError("Core diagnostic must not contain shader packs")
    validate_components(read_json(instance / "mmc-pack.json")["components"])
    return marker


def processes():
    # Never print command lines (they contain launcher authentication). Select exactly this instance.
    pattern = re.escape(INSTANCE.as_posix()).replace("/", r"[/\\]") + r"[/\\]"
    script = r"""$p = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'INSTANCE_PATTERN'
    } | ForEach-Object {
        $g = Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue
        [pscustomobject]@{pid=$_.ProcessId; window=$g.MainWindowTitle; windowOpen=($g.MainWindowHandle -ne 0); responding=$g.Responding}
    }); ConvertTo-Json -InputObject $p -Compress""".replace("INSTANCE_PATTERN", pattern.replace("'", "''"))
    result = subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True, check=True)
    return json.loads(result.stdout or "[]")


def status():
    procs = processes()
    live = read_json(GAME / "nirncraft-live-status.json")
    age = (time.time() * 1000 - live.get("updated", 0)) / 1000
    live["ageSeconds"] = round(age, 1) if live else None
    live["fresh"] = age < 15 and any(p["pid"] == live.get("pid") for p in procs)
    heartbeat = DEV / "outbox/heartbeat"
    ready = DEV / "outbox/ready"
    lines = ready.read_text().splitlines() if ready.exists() else []
    ready_pid = int(lines[0]) if lines and lines[0].isdigit() else None
    server_fresh = heartbeat.exists() and time.time() - heartbeat.stat().st_mtime < 15
    return {"instance": str(INSTANCE), "diagnosticOnly": INSTANCE_NAME == DIAGNOSTIC_NAME,
            "fullPackAcceptance": False, "running": bool(procs), "processes": procs,
            "client": live, "remoteConnected": bool(live.get("fresh") and live.get("connected")
                and live.get("worldLoaded") and live.get("isMemoryConnection") is False),
            "serverReady": not live.get("remote", False) and server_fresh and any(p["pid"] == ready_pid for p in procs),
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


def live_guard():
    # Shared across both allowlisted instances in this Prism data directory.
    return process_guard(PRISM_ROOT / ".nirncraft-live.lock", "live_world.py")


def submit(lines, timeout=900):
    with live_guard():
        return _submit(lines, timeout)


def _submit(lines, timeout=900):
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


class ProgressWatch:
    """Seconds since a busy client's progress (its status "progress": action, queue, export, settle streaks) last
    changed; no progress field (an older client) never counts as stalled."""

    def __init__(self, now):
        self.last, self.at = None, now

    def stalled_for(self, progress, now):
        if progress is None or progress != self.last:
            self.last, self.at = progress, now
            return 0.0
        return now - self.at


def live_actions(lines, timeout=180, cancel=False):
    with live_guard():
        return _live_actions(lines, timeout, cancel)


def _live_actions(lines, timeout=180, cancel=False):
    state = status()
    c = state["client"]
    if not c.get("fresh"):
        raise RuntimeError("Client control heartbeat is absent/stale; use status and wait for the world")
    if not cancel and any(c.get(k, 0) for k in ("queued", "waitTicks", "burstFrames", "settling", "commandPending")):
        # still finishing an earlier batch (a cleanup, a long export): wait while it makes progress, fail only when it
        # stalls or runs past the budget (failing at once cascaded one scene's failure into the next)
        watch, start = ProgressWatch(time.monotonic()), time.monotonic()
        while True:
            busy = any(c.get(k, 0) for k in ("queued", "waitTicks", "burstFrames", "settling", "commandPending"))
            if not busy:
                break
            if watch.stalled_for(c.get("progress"), time.monotonic()) > STALL_S or time.monotonic() - start > min(timeout, 900):
                raise RuntimeError(f"QA control queue is busy (on {c.get('lastAction')!r}, progress {c.get('progress')}); "
                                   "wait for it to finish")
            time.sleep(1.0)
            c = read_json(GAME / "nirncraft-live-status.json") or {}
    atomic(GAME / "nirncraft-live.txt", ("\n".join(lines) + "\n").encode())
    stamp = (GAME / "nirncraft-live.txt").stat().st_mtime_ns // 1_000_000
    deadline = time.monotonic() + timeout
    watch = ProgressWatch(time.monotonic())
    while time.monotonic() < deadline:
        if cancel and not processes():
            return
        c = read_json(GAME / "nirncraft-live-status.json")
        if c.get("controlVersion", 0) == stamp and not any(c.get(k, 0) for k in ("queued", "waitTicks", "burstFrames", "settling", "commandPending")):
            if c.get("error"):
                raise RuntimeError(c["error"])
            return
        if c.get("error", "").startswith("client disconnected"):  # lost the world mid-step: say why, at once
            raise RuntimeError(c["error"])
        p = c.get("progress")
        if not cancel and watch.stalled_for(p, time.monotonic()) > STALL_S:  # busy but nothing moves: say what, at once
            raise RuntimeError(f"client stalled: no progress for {STALL_S} s on {c.get('lastAction')!r} "
                               f"(queued {c.get('queued')}, settling {c.get('settling')}, command {c.get('commandPending')}, "
                               f"progress {p})")
        age = heartbeat_age(c, GAME / "nirncraft-live-status.json")
        # (a close: the client stops writing its status while it shuts down and saves the world; it is not a hang
        # while the process is still on its way out; the deadline still bounds it)
        if age > HEARTBEAT_STALE and not cancel:  # the client writes its status every second, loading screens included
            alive = bool(processes())
            raise RuntimeError(f"client stopped responding: heartbeat {age:.0f} s old (screen {c.get('screen')}, "
                               f"process {'alive' if alive else 'gone'})")
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
    core = GAME / "mods/nirncraft-core.jar"
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
    replacements = {"nirncraft/baked/" + n: (folder / n).read_bytes() for n in [*result, "manifest.json"]}
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
    source=(REPO/'mod/src/main/java/com/nirncraft/bake/Bake.java').read_text()
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
    for name in array('MAP_FILES'):feed('nirncraft/tamriel/'+name)
    index='nirncraft/tamriel/places/index.json'
    if index in names:
        raw=archive.read(index);h.update(raw)
        for name in json.loads(raw):feed('nirncraft/tamriel/places/'+name)
    for name in array('DATA_FILES'):feed(name.lstrip('/'))
    index='nirncraft/tamriel/cityplans/index.json'
    if index in names:
        raw=archive.read(index);h.update(raw)
        for name in json.loads(raw).get('plans',[]):feed('nirncraft/tamriel/cityplans/'+name+'.json')
    for name in array('CLASSES'):feed(name.replace('.','/')+'.class')
    return h.hexdigest()


def stage_core(jar, folder, output):
    """Stage runtime bakes into a rebuilt core only when its declared dependencies match the verified JVM."""
    jar, folder, output = Path(jar), Path(folder), Path(output)
    validate_core_jar(jar)
    core = GAME / 'mods/nirncraft-core.jar'
    receipt = read_json(DEV / 'outbox/runtime.json')
    runtime_sha = hashlib.sha256(core.read_bytes()).hexdigest()
    if receipt.get('coreSha256') != runtime_sha:
        raise ValueError('Runtime receipt does not match installed core; launch the intended core first')
    expected = (DEV / 'outbox/bake-packaged-inputs').read_text().strip()
    validated = validate_bakes(folder, expected)
    with zipfile.ZipFile(core) as old, zipfile.ZipFile(jar) as new:
        bake_class = 'com/nirncraft/bake/Bake.class'
        if (old.read(bake_class) != new.read(bake_class) or jar_bake_inputs(old) != expected
                or jar_bake_inputs(new) != expected):
            raise ValueError('Rebuilt core has different bake dependencies; compute and verify bakes in that runtime')
        if output.resolve() in (core.resolve(), jar.resolve()) or output.exists():
            raise ValueError('Stage to a new artifact path; never replace the installed or input core')
        replacements = {'nirncraft/baked/' + n: (folder / n).read_bytes() for n in [*validated, 'manifest.json']}
        output.parent.mkdir(parents=True, exist_ok=True)
        tmp = output.with_suffix('.staging')
        try:
            with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as target:
                for entry in new.infolist():
                    if entry.filename not in replacements: target.writestr(entry, new.read(entry))
                for name, raw in replacements.items(): target.writestr(name, raw)
            os.replace(tmp, output)
        finally:
            if tmp.exists(): tmp.unlink()
    result = {'output': str(output), 'sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
              'inputCoreSha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'runtimeCoreSha256': runtime_sha,
              'bakeInputs': expected, 'bakes': validated,
              'scope': 'Dependency-verified staging; runtime checks and visual acceptance still required.'}
    atomic(output.with_suffix('.receipt.json'), (json.dumps(result, indent=2) + '\n').encode())
    print(json.dumps(result, indent=2))


def install_core(jar):
    """Preserve runtime plans only when every changed entry is in known preview/render paths."""
    if processes():
        raise RuntimeError('Close the disposable preview before installing code')
    validate_core_jar(jar)
    core = GAME / 'mods/nirncraft-core.jar'
    receipt = read_json(DEV / 'outbox/runtime.json')
    known = receipt.get('coreSha256') == hashlib.sha256(core.read_bytes()).hexdigest()
    with zipfile.ZipFile(core) as old, zipfile.ZipFile(jar) as new:
        old_names,new_names=set(old.namelist()),set(new.namelist())
        names = old_names | new_names
        changed = [n for n in sorted(names) if n not in old_names or n not in new_names
                   or old.read(n) != new.read(n)]
        safe = known and all(n.startswith(('com/nirncraft/client/autotest/', 'com/nirncraft/fire/client/',
                                          'com/nirncraft/atmosphere/client/', 'assets/nirncraft/shaders/', 'nirncraft/baked/'))
                             or n in ('nirncraft/generation-identity.txt','assets/nirncraft/textures/environment/smoke_sources.png')
                             or any(n==p+'.class' or n.startswith(p+'$') for p in
                                    ('com/nirncraft/inventory/PackSelfTest','com/nirncraft/inventory/OverflowSelfTest')) for n in changed)
        expected=(DEV/'outbox/bake-packaged-inputs').read_text().strip() if known else ''
        bake_class='com/nirncraft/bake/Bake.class'
        if known and bake_class in old_names and bake_class in new_names and old.read(bake_class)==new.read(bake_class):
            # QA/server ticket changes need not trigger an expensive rebake when
            # every declared bake dependency still has the exact runtime hash.
            safe=jar_bake_inputs(old)==expected and jar_bake_inputs(new)==expected
        replacements = {}
        fresh_verified_bakes = False
        if safe:
            def verified_entries(archive):
                manifest = json.loads(archive.read('nirncraft/baked/manifest.json'))
                names = {n + '.bin' for n in ('landmass', 'roadplan', 'streetplan', 'sceneplan')}
                if manifest.get('format') != 1 or set(manifest.get('files', [])) != names or manifest.get('inputs') != expected:
                    raise ValueError('Packaged bake manifest is stale or incomplete')
                entries = {'nirncraft/baked/manifest.json': archive.read('nirncraft/baked/manifest.json')}
                for name in names:
                    raw = archive.read('nirncraft/baked/' + name)
                    if decode_bake(raw)[0] != expected: raise ValueError('Packaged bake has mixed inputs')
                    entries['nirncraft/baked/' + name] = raw
                return entries
            try:
                replacements = verified_entries(old)
            except (ValueError, KeyError):
                # A core can have computed live because its embedded bake was stale. A staged,
                # fully verified replacement should replace that bake, never inherit it.
                try:
                    verified_entries(new)
                    fresh_verified_bakes = True
                except (ValueError, KeyError) as e:
                    # neither jar carries a bake for these inputs: install the code anyway (the game computes the
                    # plans at start, slower) and say so; a stale packaged bake must never block a code install
                    print(f'WARNING: no packaged bake matches the runtime inputs ({e}); the game computes the plans at '
                          'start. Rebake: tools/bake/bake.ps1', file=sys.stderr)
                safe = False
        backup = DEV / 'core-backups' / str(time.time_ns()) / 'nirncraft-core.jar'
        backup.parent.mkdir(parents=True)
        shutil.copy2(core, backup)
        tmp = core.with_suffix('.preview-tmp')
        with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as target:
            for entry in new.infolist():
                target.writestr(entry, replacements.get(entry.filename, new.read(entry)))
    os.replace(tmp, core)
    print(json.dumps({'installed': str(jar), 'backup': str(backup), 'changedEntries': changed,
                      'installedSha256': hashlib.sha256(core.read_bytes()).hexdigest(),
                      'verifiedRuntimeBakesPreserved': safe, 'freshVerifiedBakes': fresh_verified_bakes,
                      'restartRequired': True}, indent=2))


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
    staged = sub.add_parser('stage-core')
    staged.add_argument('jar', type=Path)
    staged.add_argument('bakes', type=Path)
    staged.add_argument('output', type=Path)
    args = ap.parse_args()
    with nullcontext() if args.action in ("status", "storage", "profile") else live_guard():
        dispatch(args)


def dispatch(args):
    if INSTANCE_NAME == DIAGNOSTIC_NAME and args.action in ("shader", "upload-bakes", "install-core", "stage-core"):
        raise ValueError("Build and prepare a new core diagnostic archive instead of modifying its recorded core")
    if args.action == 'stage-core':
        stage_core(args.jar, args.bakes, args.output)
    elif args.action == 'install-core':
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
        flags = "-Dnirncraft.dev=world-preview -Dnirncraft.preview.resources=world-preview/resources"
        if not re.search(r'^JvmArgs=', text, re.M):
            raise RuntimeError("Instance has no JvmArgs entry; prepare the disposable autotest instance first")
        if "-Dnirncraft.dev=" in text and any(flag not in text for flag in flags.split()):
            raise RuntimeError("Instance has different preview paths; review its launch flags before enabling")
        if "-Dnirncraft.dev=" not in text:
            text = re.sub(r'^JvmArgs="?(.*?)"?$', lambda m: 'JvmArgs="' + m[1].strip('"') + ' ' + flags + '"', text, flags=re.M)
        atomic(cfg, text.encode())
        print("Preview flags enabled. No normal Prism instance changed.")
    elif args.action == "launch":
        if processes():
            print("Preview already running; use status")
            return
        if not INSTANCE.is_dir():
            raise RuntimeError("Prepare the disposable instance first with tools/qa/prism_autotest.ps1")
        validate_core_jar(GAME / "mods/nirncraft-core.jar")
        components = json.loads((INSTANCE / "mmc-pack.json").read_text(encoding="utf-8-sig"))["components"]
        validate_components(components)
        if INSTANCE_NAME == DIAGNOSTIC_NAME:
            validate_diagnostic_instance(INSTANCE)
        # A second native preview would compete for memory and invalidate performance captures.
        sys.path.insert(0, str(REPO / "tools/qa"))
        from prepare_prism_263 import exact_processes
        other = TARGET["instanceName"] if INSTANCE_NAME == DIAGNOSTIC_NAME else DIAGNOSTIC_NAME
        if exact_processes(PRISM_ROOT, other):
            raise RuntimeError("Another disposable native client is running; close it cleanly first")
        atomic(GAME / "nirncraft-live.txt", b"# New preview session; idle\n")
        launcher = Path(os.environ["LOCALAPPDATA"]) / "Programs/PrismLauncher/prismlauncher.exe"
        subprocess.Popen([str(launcher), "--dir", str(PRISM_ROOT), "--launch", INSTANCE_NAME], creationflags=subprocess.CREATE_NO_WINDOW)
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
            if log.exists() and "Autotest live: ready" in log.read_text(encoding="utf-8", errors="replace"):
                atomic(GAME / "nirncraft-live.txt", b"stop\n")
            else:
                # No controller (a menu, a disconnect screen, a failed join): ask this exact instance's game window to
                # close, as its close button does (WM_CLOSE; Minecraft shuts down and saves normally). Never a kill.
                for p in processes():
                    subprocess.run(["powershell", "-NoProfile", "-Command",
                                    f"$g = Get-Process -Id {int(p['pid'])} -ErrorAction SilentlyContinue; "
                                    "if ($g) { [void]$g.CloseMainWindow() }"], check=False)
                print("No live controller: requested a normal window close")
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
        submit(["nirncraft-dev reload " + args.target, "nirncraft-dev bake-status"])
    elif args.action == "regen":
        a, b, c, d = args.bounds
        x1, x2, z1, z2 = min(a, c), max(a, c), min(b, d), max(b, d)
        chunks = (x2 // 16 - x1 // 16 + 1) * (z2 // 16 - z1 // 16 + 1)
        if chunks > 256:
            raise ValueError("Preview regeneration is limited to 256 chunks; split the area")
        # Existing regen needs all players and their chunk tickets far from the area.
        submit(["@timeout 900", f"tp @a {x2+2048} 360 {z2+2048}",
                f"nirncraft-dev regen {x1} {z1} {x2} {z2}", "nirncraft-dev status"])
        print("Fresh area ready; use a dungeon view/camera command to return. Release tickets when done.")
    elif args.action == "shader":
        # The old Complementary Reimagined patch path is gone; Infinite ATLAS is the only shader pack.
        raise RuntimeError("live_world.py shader is retired: edit shaderpack/InfiniteATLAS and run "
                           "`py -3 tools/harness/harness.py watch` (rebuilds the pack zip and reloads Iris); "
                           "see tools/harness/README.md")
    elif args.action == "upload-bakes":
        upload_bakes(args.folder)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, OSError, KeyError, zipfile.BadZipFile, zlib.error) as exc:
        print("ERROR:", exc, file=sys.stderr)
        sys.exit(1)
