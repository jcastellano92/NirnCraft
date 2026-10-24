"""Live game control for the harness: one disposable preview client (NirnCraft-26.3-autotest), driven through
tools/dev/live_world.py's bridge. Owns launch per restart group, the shader / resource / data / jar reload kinds, and
running one scene per action batch (a failing scene clears only its own batch).
"""
import filecmp
import os
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/dev"))
sys.path.insert(0, str(ROOT / "tools"))
import live_world as lw  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
import trace as tr  # noqa: E402

GAME = lw.GAME
SHOTS = GAME / "build/autotest-screenshots"
PERF = GAME / "build/autotest-performance"
STATE = ROOT / ".work/harness/client.json"
# the live lease beside the one Prism instance, not in a checkout: every checkout (the agents' worktrees) sees who holds
# the game client (each worktree had its own lease and could start the client under another's run)
LEASE = GAME / "nirncraft-live-lease.json"
LEASE_OWNER = f"harness:{ROOT.resolve()}"
LEASE_STALE_S = 600  # a holder that has not refreshed its heartbeat for this long is gone
PACK_SRC = ROOT / "shaderpack"
PACK_ZIP = GAME / "shaderpacks/InfiniteATLAS.zip"
OVERLAY = GAME / "resourcepacks/nirncraft-dev"
ASSETS = ROOT / "mod/src/main/resources/assets"
MARK = ("-Dnirncraft.harness=1", "-Dnirncraft.harness.end=1")  # brackets the group's flags in JvmArgs


def log(msg):
    print(msg, flush=True)


def sha(path):
    try:
        return hashlib.sha256(Path(path).read_bytes()).hexdigest()[:16]
    except OSError:
        return "-"


def inputs():
    """Build inputs every live result depends on (feeds scene fingerprints)."""
    overlay = hashlib.sha256()
    if OVERLAY.exists():
        for f in sorted(OVERLAY.rglob("*")):
            if f.is_file():
                overlay.update(f.relative_to(OVERLAY).as_posix().encode() + f.read_bytes())
    return {"core": sha(GAME / "mods/nirncraft-core.jar"), "pack": sha(PACK_ZIP), "overlay": overlay.hexdigest()[:16]}


# ----------------------------------------------------------------------------------------------- lease + state
def lease_holder(path=None):
    """The live lease's holder while it is fresh ({"owner", "heartbeat", ...}), else None."""
    path = path or LEASE
    try:
        held = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if time.time() - float(held.get("beat", 0)) > LEASE_STALE_S:
        return None
    return held


def take_lease(owner=None, path=None):
    """The game client for this checkout's harness; SystemExit while another checkout's run holds it."""
    owner, path = owner or LEASE_OWNER, Path(path or LEASE)
    held = lease_holder(path)
    if held and held.get("owner") != owner:
        raise SystemExit(f"live lease held by {held.get('owner')} ({int(time.time() - float(held['beat']))} s ago); wait or ask its owner")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"owner": owner, "system": "claude-code", "job": "harness", "instance": lw.INSTANCE_NAME,
                                "beat": time.time(), "heartbeat": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}),
                    encoding="utf-8")


def refresh_lease(path=None):
    """Keeps this checkout's lease fresh (a run longer than LEASE_STALE_S looked abandoned: its heartbeat was the take's)."""
    held = lease_holder(path)
    if held and held.get("owner") == LEASE_OWNER:
        take_lease(path=path)


def drop_lease(path=None):
    held = lease_holder(path)
    if not held or held.get("owner") == LEASE_OWNER:
        Path(path or LEASE).unlink(missing_ok=True)


def client_state():
    try:
        return json.loads(STATE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def running():
    return bool(lw.processes())


def ready():
    st = lw.status()
    c = st.get("client", {})
    return bool(c.get("fresh") and (st.get("serverReady") or (c.get("remote") and c.get("worldLoaded"))))


# ----------------------------------------------------------------------------------------------- launch / close
def _set_group_flags(jvm):
    cfg = lw.INSTANCE / "instance.cfg"
    text = cfg.read_text(encoding="utf-8")
    m = re.search(r'^JvmArgs="?(.*?)"?$', text, re.M)
    if not m:
        raise SystemExit("instance has no JvmArgs; prepare it with tools/qa/prism_autotest.ps1 -PrepareOnly -Live")
    args = re.sub(re.escape(MARK[0]) + r".*?" + re.escape(MARK[1]), "", m.group(1)).strip()
    extra = " ".join(f"-D{k}={v}" for k, v in sorted(jvm.items()))
    if extra:
        args = f"{args} {MARK[0]} {extra} {MARK[1]}"
    text = text[:m.start()] + f'JvmArgs="{args}"' + text[m.end():]
    lw.atomic(cfg, text.encode())
    write_flags_file(jvm)


def drop_stale_overlay(overlay=None, sources=None):
    """Overlay files whose source in the mod's resources is newer are gone: the overlay sits above the jar, so a copy
    left there from an earlier live edit hid the jar's newer file (a stale shader include: undeclared uniforms, the
    client crashed compiling the pipeline at load). Returns the paths dropped."""
    overlay = Path(overlay or OVERLAY) / "assets"
    sources = Path(sources or ROOT / "mod/src/main/resources") / "assets"
    dropped = []
    if not overlay.exists():
        return dropped
    for f in overlay.rglob("*"):
        if not f.is_file():
            continue
        src = sources / f.relative_to(overlay)
        if src.exists() and src.stat().st_mtime > f.stat().st_mtime:
            f.unlink()
            dropped.append(f)
    return dropped


FLAGS_FILE = GAME / "nirncraft-harness-flags.properties"


def write_flags_file(jvm, path=None):
    """The group's flags where the mod reads them at startup (NirnCraft.harnessFlags): Prism keeps its own copy of the
    instance settings and wrote it back over the JvmArgs edit at launch, so flags went missing from runs silently."""
    path = Path(path or FLAGS_FILE)
    if not jvm:
        path.unlink(missing_ok=True)
        return
    lines = [f"{k}={str(v).replace(chr(92), '/')}" for k, v in sorted(jvm.items())]
    lw.atomic(path, ("\n".join(lines) + "\n").encode())


def _enable_overlay():
    """The dev overlay resource pack sits above the mod's own assets, so edited textures / models / sounds / lang
    reload live (`resources` verb) without a jar rebuild. Enabled in options.txt while the client is closed."""
    (OVERLAY / "assets").mkdir(parents=True, exist_ok=True)
    drop_stale_overlay()
    meta = OVERLAY / "pack.mcmeta"
    mod_meta = json.loads((ROOT / "mod/src/main/resources/pack.mcmeta").read_text(encoding="utf-8"))["pack"]
    want = {"pack": {"description": "NirnCraft harness dev overlay", "min_format": mod_meta["min_format"],
                     "max_format": mod_meta["max_format"]}}  # the formats the mod's own resources declare
    if not meta.exists() or json.loads(meta.read_text(encoding="utf-8")) != want:
        meta.write_text(json.dumps(want), encoding="utf-8")
    opts = GAME / "options.txt"
    if opts.exists():
        text = opts.read_text(encoding="utf-8")
        # clicking elsewhere must not pause the test world (the pause menu stops the integrated server)
        if re.search(r"^pauseOnLostFocus:", text, re.M):
            text = re.sub(r"^pauseOnLostFocus:.*$", "pauseOnLostFocus:false", text, flags=re.M)
        else:
            text += "pauseOnLostFocus:false" + chr(10)
        m = re.search(r"^resourcePacks:(.*)$", text, re.M)
        packs = json.loads(m.group(1)) if m else []
        if "file/nirncraft-dev" not in packs:
            packs = [p for p in packs if p != "file/nirncraft-dev"] or ["vanilla", "mod_resources"]
            packs.append("file/nirncraft-dev")
            line = "resourcePacks:" + json.dumps(packs, separators=(",", ":"))
            text = text[:m.start()] + line + text[m.end():] if m else text + line + "\n"
            opts.write_text(text, encoding="utf-8")


FRESH_WORLD = {"on": False}  # harness --fresh-world: make the test world again this launch


def kept_world():
    """The test world for this world generation: kept between runs (places generated once load from disk in seconds);
    a worldgen change (code or data) gets a new one; worlds of older generations are deleted."""
    import cameras
    name = f"harness_{cameras.worldgen_hash()}"
    saves = GAME / "saves"
    if saves.exists():
        for d in saves.iterdir():
            if d.is_dir() and d.name.startswith("harness_") and (d.name != name or FRESH_WORLD["on"]):
                shutil.rmtree(d, ignore_errors=True)
    return name


def _iris_enable():
    """Every launch starts with the shader pack on (a shaders-off variant saves enableShaders=false into Iris's config,
    which would turn every later "shaders" capture into a vanilla one)."""
    cfg = GAME / "config/iris.properties"
    if cfg.exists():
        text = cfg.read_text(encoding="utf-8")
        if re.search(r"^enableShaders=false", text, re.M):
            cfg.write_text(re.sub(r"^enableShaders=false", "enableShaders=true", text, flags=re.M), encoding="utf-8")


def launch(group, groups, limit=900, attempt=1):
    """Starts the client and waits for its world. A client that dies while loading (before the world is up: a driver
    or window-creation crash such as 0xC000041D) is started once more; a second death fails the launch."""
    try:
        return _launch(group, groups, limit)
    except RuntimeError as e:
        if attempt == 1 and "exited while loading" in str(e):
            log(f"  the client died while loading ({e}); starting it once more")
            tr.step("launch_retry", group=group, error=str(e)[:300])
            return launch(group, groups, limit, attempt=2)
        raise


def _launch(group, groups, limit=900):
    tr.step("launch", group=group)
    _iris_enable()
    cfg = groups.get(group, {})
    jvm = dict(cfg.get("jvm", {}))
    if "nirncraft.autotest.world" not in jvm and (group == "live" or cfg.get("keptWorld")):
        jvm.update({"nirncraft.autotest.world": kept_world(), "nirncraft.autotest.keepWorld": "true"})
    _set_group_flags(jvm)
    _enable_overlay()
    code = subprocess.run([sys.executable, str(ROOT / "tools/dev/live_world.py"), "launch"], capture_output=True, text=True)
    if code.returncode:
        raise RuntimeError("launch failed: " + (code.stdout + code.stderr)[-600:])
    t0, gone = time.monotonic(), 0
    while time.monotonic() - t0 < limit:
        if ready():
            STATE.parent.mkdir(parents=True, exist_ok=True)
            STATE.write_text(json.dumps({"group": group, "pid": (lw.processes() or [{}])[0].get("pid"),
                                         "shaders": True, "since": time.time()}), encoding="utf-8")
            log(f"  client ready ({group}) after {time.monotonic() - t0:.0f} s; it runs behind your windows")
            send_to_back()
            tr.step("client_ready", group=group, seconds=round(time.monotonic() - t0, 1))
            return
        gone = gone + 1 if not running() else 0
        if gone >= 10 and time.monotonic() - t0 > 60:
            raise RuntimeError("client exited while loading; see logs/latest.log")
        time.sleep(3)
    raise RuntimeError("world not ready in time; client left running for inspection")


def close():
    tr.step("close", running=running())
    if not running():
        STATE.unlink(missing_ok=True)
        return
    r = subprocess.run([sys.executable, str(ROOT / "tools/dev/live_world.py"), "close"], capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError("clean close failed: " + (r.stdout + r.stderr)[-400:])
    STATE.unlink(missing_ok=True)


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "dev"))
import buildpaths  # noqa: E402  (a worktree builds into its own directory)
BUILT = buildpaths.build_dir() / "libs/nirncraft-0.1.0.jar"


def stale_core():
    """A message when the installed core is older than the latest build (tests would run yesterday's code)."""
    inst = GAME / "mods/nirncraft-core.jar"
    if BUILT.exists() and inst.exists() and BUILT.stat().st_mtime > inst.stat().st_mtime + 60 and sha(BUILT) != sha(inst):
        return (f"the installed core ({time.ctime(inst.stat().st_mtime)}) is older than the latest build "
                f"({time.ctime(BUILT.stat().st_mtime)}): run with --rebuild, or tools/dev/live_world.py install-core")
    return None


_CURRENT = {"checked": False}

NATIVE_BUILT = ROOT / "native/build/Release/atlas_core.dll"
NATIVE_SHIPPED = ROOT / "mod/src/main/resources/natives/windows-x86_64/atlas_core.dll"


def native_stale(built=NATIVE_BUILT, sources=None, digest=None):
    """Whether native sources (src, shaders, include) are newer than the built atlas_core, unless the build stamp beside
    it says it was built from these very sources (a change to a file the core does not use, a tool's shader, leaves
    the linker nothing to do and the core's time where it was: without the stamp every run rebuilt it)."""
    sources = sources or [ROOT / "native/src", ROOT / "native/shaders", ROOT / "native/include"]
    newest = max((f.stat().st_mtime for d in sources for f in Path(d).rglob("*") if f.is_file()), default=0)
    if built.exists() and newest <= built.stat().st_mtime + 1:
        return False
    stamp = built.with_suffix(".sources")
    return not (built.exists() and digest and stamp.exists() and stamp.read_text().strip() == digest)


def sync_native():
    """The far-field core the game loads is the one built from today's sources: rebuilt when its sources changed
    (native/build.ps1 -NoTest; the native suites run in the check tier), and copied into the mod's resources when it
    differs from the shipped one (the jar rebuild and install that follow put it in the game). Before this, a shader or
    native change reached the game only when someone copied the DLL by hand. Windows only (the Linux .so is built in
    WSL: tools/dev/check_natives.py guards its ABI)."""
    if os.name != "nt":
        return
    import native_sources  # tools/dev: which sources a core was built from (check_natives --require-fresh)
    digest = native_sources.source_hash()
    if not NATIVE_BUILT.exists() and NATIVE_SHIPPED.exists() and not native_sources.stale("windows-x86_64", digest):
        return  # a worktree with no native build of its own: the shipped core was built from these very sources
    if native_stale(digest=digest):
        log("  native sources changed: building atlas_core (once for this run)")
        tr.step("auto_native_build")
        r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "native/build.ps1"),
                            "-NoTest"], capture_output=True, text=True)
        if r.returncode or not NATIVE_BUILT.exists():
            raise RuntimeError("atlas_core build failed (tests would run a stale far field):\n" + (r.stdout + r.stderr)[-1200:])
        NATIVE_BUILT.with_suffix(".sources").write_text(digest)
    if NATIVE_BUILT.exists() and (not NATIVE_SHIPPED.exists() or sha(NATIVE_BUILT) != sha(NATIVE_SHIPPED)):
        log("  shipping the current atlas_core into the mod (it differed from the built one)")
        tr.step("auto_native_ship")
        shutil.copy2(NATIVE_BUILT, NATIVE_SHIPPED)
        native_sources.record("windows-x86_64", digest)
    elif NATIVE_BUILT.exists() and not native_stale(digest=digest) and native_sources.stale("windows-x86_64", digest):
        # built from today's sources and byte for byte the shipped one: the shipped core is current, so say so (else
        # check-natives --require-fresh failed forever after a change that left the core's bytes as they were)
        native_sources.record("windows-x86_64", digest)


def ensure_current():
    """Once per run, before the first test that needs the client: tests never run stale code. The jar is built when a
    mod source is newer than the built jar (the generation and far identities come with it, so a world-generation
    change also starts fresh far caches); the installed core is replaced when it differs (the client restarts); the
    shader pack is synced from its source. One build for the whole run: a run never changes code half way."""
    if _CURRENT["checked"] or os.environ.get("NIRNCRAFT_HARNESS_NO_BUILD") == "1":
        return
    _CURRENT["checked"] = True
    sync_native()
    src = ROOT / "mod/src/main"
    newest = max((f.stat().st_mtime for f in src.rglob("*") if f.is_file()), default=0)
    if not BUILT.exists() or newest > BUILT.stat().st_mtime + 1:
        log("  sources changed since the last build: building the jar (once for this run)")
        tr.step("auto_build")
        r = build263(["jar", "--offline"])
        if r.returncode:
            raise RuntimeError("jar build failed (tests would run stale code):\n" + build_failure(r))
    inst = GAME / "mods/nirncraft-core.jar"
    if BUILT.exists() and (not inst.exists() or sha(BUILT) != sha(inst)):
        log("  installing the current build (the installed core was older)")
        tr.step("auto_install")
        if running():
            close()
        r = subprocess.run([sys.executable, str(ROOT / "tools/dev/live_world.py"), "install-core", str(BUILT)],
                           capture_output=True, text=True)
        if r.returncode:
            raise RuntimeError("install-core failed: " + (r.stdout + r.stderr)[-600:])
    try:
        if sync_pack():
            log("  shader pack updated from its source")
            if running() and ready():
                actions(["reload"], timeout=600)
    except RuntimeError as e:
        raise RuntimeError(f"shader pack build failed (tests would run a stale pack): {e}")


def ensure(group, groups):
    """The client for `group`, reusing a running one when it was booted for the same group (and the current build)."""
    ensure_current()
    msg = stale_core()
    if msg:
        log("  ! " + msg)
    start_watchdog()
    if running() and client_state().get("group") == group and ready():
        return
    if running():
        log(f"  restart: client is in group {client_state().get('group')!r}, need {group!r}")
        close()
    launch(group, groups)


def actions(lines, timeout=900):
    """One batch to the live client, traced: the verbs, how long, the outcome, the client status after."""
    t0 = time.monotonic()
    try:
        lw.live_actions(lines, timeout=timeout)
        tr.step("actions", lines=lines, seconds=round(time.monotonic() - t0, 1), ok=True, status=_status_brief())
    except Exception as e:
        tr.step("actions", lines=lines, seconds=round(time.monotonic() - t0, 1), ok=False, error=str(e)[-600:],
                status=_status_brief(), running=running())
        raise


def _status_brief():
    s = lw.read_json(GAME / "nirncraft-live-status.json") or {}
    return {k: s.get(k) for k in ("lastAction", "screen", "queued", "settling", "commandPending", "error",
                                  "worldLoaded") if k in s}


def shaders(on):
    st = client_state()
    if st.get("shaders") == on:
        return
    actions([f"shaders {'on' if on else 'off'}"], timeout=600)
    st["shaders"] = on
    STATE.write_text(json.dumps(st), encoding="utf-8")


# ----------------------------------------------------------------------------------------------- reload kinds
def sync_pack():
    """Build the pack zip (glslang-validated, as shipped) and install it; returns True when it changed."""
    r = subprocess.run([sys.executable, "-I", str(PACK_SRC / "build_pack.py")], capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError("pack build failed:\n" + (r.stdout + r.stderr)[-1500:])
    built = PACK_SRC / "build/InfiniteATLAS.zip"
    if PACK_ZIP.exists() and filecmp.cmp(built, PACK_ZIP, shallow=False):
        return False
    shutil.copy2(built, PACK_ZIP)
    return True


def sync_overlay(paths=None):
    """Copy edited mod assets into the overlay pack (all of them when paths is None); returns the count."""
    n = 0
    src = [Path(p) for p in paths] if paths else [p for p in ASSETS.rglob("*") if p.is_file()]
    for p in src:
        p = p.resolve()
        try:
            rel = p.relative_to(ASSETS.resolve())
        except ValueError:
            continue
        dst = OVERLAY / "assets" / rel
        if p.exists():
            if dst.exists() and filecmp.cmp(p, dst, shallow=False):
                continue
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(p, dst)
        else:
            dst.unlink(missing_ok=True)
        n += 1
    return n


PACK_OPTIONS = GAME / "shaderpacks" / (PACK_ZIP.name + ".txt")  # Iris' option file for the pack


def options_key(opts):
    """A scene's pack options as one comparable string ('' = the pack's defaults)."""
    if not opts:
        return ""
    return ";".join(f"{k}={str(v).lower() if isinstance(v, bool) else v}" for k, v in sorted(opts.items()))


def pack_options(opts):
    """Puts the pack in the scene's options (Iris' option file, then a shader reload while the client runs); the
    pack's defaults for None / {}. Only when they differ from what is loaded: scenes are ordered so that runs of the
    same options share one reload. Returns True when it reloaded."""
    want = options_key(opts)
    st = client_state()
    have = st.get("packOptions", options_key(_read_options()))
    if have == want:
        return False
    tr.step("pack_options", options=want or "(defaults)")
    PACK_OPTIONS.parent.mkdir(parents=True, exist_ok=True)
    if want:
        PACK_OPTIONS.write_text("\n".join(want.split(";")) + "\n", encoding="utf-8")
    else:
        PACK_OPTIONS.unlink(missing_ok=True)
    if running():
        actions(["reload"], timeout=600)
    st["packOptions"] = want
    if STATE.exists() or st:
        STATE.parent.mkdir(parents=True, exist_ok=True)
        STATE.write_text(json.dumps(st), encoding="utf-8")
    return True


def _read_options():
    try:
        return dict(l.split("=", 1) for l in PACK_OPTIONS.read_text(encoding="utf-8").splitlines() if "=" in l)
    except OSError:
        return {}


def reload_shaders():
    tr.step("reload", kind="shaders")
    since = (GAME / "logs/latest.log").stat().st_size if (GAME / "logs/latest.log").exists() else 0
    changed = sync_pack()
    if changed and running():
        actions(["reload"], timeout=600)
    return changed, shader_errors(since)


def reload_resources(paths=None):
    tr.step("reload", kind="resources", files=len(paths or []))
    n = sync_overlay(paths)
    if n and running():
        actions(["resources"], timeout=900)
    return n


BUILD_BUSY_WAIT_S = 1200  # another build holds the lock (an agent, a hand build): wait for it, do not fail the run


def build263(args, wait_s=None, poll_s=15, run=subprocess.run):
    """tools/dev/build_263.py with args; while another build holds its lock ("Resource busy ... no work started"),
    waits and tries again (a run started next to a hand build failed every scene with "jar build failed")."""
    deadline = time.monotonic() + (BUILD_BUSY_WAIT_S if wait_s is None else wait_s)
    while True:
        r = run([sys.executable, str(ROOT / "tools/dev/build_263.py"), *args], capture_output=True, text=True)
        if r.returncode == 0 or "Resource busy" not in (r.stdout + r.stderr) or time.monotonic() > deadline:
            return r
        log("  another build holds the lock: waiting for it")
        time.sleep(poll_s)


def build_failure(r):
    """A failed build's reason: its error lines, or (none) its last lines (a busy lock said nothing at all)."""
    lines = (r.stdout + r.stderr).splitlines()
    tail = [l for l in lines if "error:" in l or "FAILED" in l][:12]
    return "\n".join(tail or lines[-8:])


def reload_data(target):
    tr.step("reload", kind="data", target=target)
    r = subprocess.run([sys.executable, str(ROOT / "tools/dev/live_world.py"), "reload", target],
                       capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError(f"data reload {target} failed: " + (r.stdout + r.stderr)[-600:])


def rebuild_jar():
    """Gradle build (with tests) + install into the preview; the client must restart to load it."""
    tr.step("rebuild_jar")
    r = build263(["--offline", "build"])
    if r.returncode:
        tail = [l for l in (r.stdout + r.stderr).splitlines() if "error:" in l or "FAILED" in l][:12]
        raise RuntimeError("jar build failed:\n" + "\n".join(tail))
    jar = next(iter(sorted((buildpaths.build_dir() / "libs").glob("nirncraft-*.jar"))), None)
    if jar is None:
        raise RuntimeError("built jar not found")
    if running():
        close()
    r = subprocess.run([sys.executable, str(ROOT / "tools/dev/live_world.py"), "install-core", str(jar)],
                       capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError("install-core failed: " + (r.stdout + r.stderr)[-600:])


ERR = re.compile(r"(error|failed|exception|could not|unable to)", re.I)


def shader_errors(since):
    p = GAME / "logs/latest.log"
    if not p.exists():
        return []
    with open(p, "rb") as f:
        f.seek(since)
        text = f.read().decode("utf-8", "replace")
    out = []
    for line in text.splitlines():
        low = line.lower()
        if ("iris" in low or "shader" in low or "glsl" in low) and ERR.search(line) and "option menu" not in low:
            out.append(line.strip()[:300])
    return out


# ----------------------------------------------------------------------------------------------- crashes
def crashed():
    """True when the client the harness launched is gone without the harness closing it (close() clears STATE)."""
    st = client_state()
    return bool(st.get("pid")) and not running()


def closed_normally(since):
    """The client went away through a normal shutdown (someone closed its window) rather than a crash: no crash report
    since `since` and the log ends with Minecraft's "Stopping!"."""
    reports = [r for r in (GAME / "crash-reports").glob("*.txt") if r.stat().st_mtime >= since - 5] \
        if (GAME / "crash-reports").exists() else []
    natives = [f for d in (GAME, lw.INSTANCE) for f in d.glob("hs_err_pid*.log") if f.stat().st_mtime >= since - 5]
    if reports or natives or not LOG_FILE.exists():
        return False
    tail = LOG_FILE.read_text(encoding="utf-8", errors="replace").splitlines()[-40:]
    return any("Stopping!" in l for l in tail)


def crash_evidence(dest, since):
    """Copy what explains a crash next to the test's result: the newest crash report, a JVM native crash file
    (hs_err_pid*.log), the last 400 log lines. Returns (path, one-line cause)."""
    parts, cause = [], ""
    reports = sorted((GAME / "crash-reports").glob("*.txt"), key=lambda f: f.stat().st_mtime) \
        if (GAME / "crash-reports").exists() else []
    reports = [r for r in reports if r.stat().st_mtime >= since - 5]
    if reports:
        text = reports[-1].read_text(encoding="utf-8", errors="replace")
        parts.append(f"===== {reports[-1].name}\n{text}")
        m = re.search(r"^Description: (.*)$", text, re.M)
        ex = re.search(r"^([\w.$]+(?:Exception|Error)[^\n]*)", text, re.M)
        cause = (m.group(1) if m else "") + (": " + ex.group(1)[:160] if ex else "")
    natives = [f for d in (GAME, lw.INSTANCE) for f in d.glob("hs_err_pid*.log") if f.stat().st_mtime >= since - 5]
    for f in natives[-1:]:
        text = f.read_text(encoding="utf-8", errors="replace")
        parts.append(f"===== {f.name}\n{text[:60000]}")
        m = re.search(r"^# Problematic frame:\n# (.*)$", text, re.M)
        cause = cause or ("native crash: " + (m.group(1) if m else "see hs_err"))
    log_file = GAME / "logs/latest.log"
    if log_file.exists():
        lines = log_file.read_text(encoding="utf-8", errors="replace").splitlines()
        parts.append("===== logs/latest.log (last 400 lines)\n" + "\n".join(lines[-400:]))
        if not cause:
            err = [l for l in lines[-400:] if "ERROR" in l or "Exception" in l]
            cause = err[-1][-200:] if err else "the client exited without a crash report"
    tr.step("crash_evidence", dest=str(dest), cause=cause[:300])
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():  # like images: the newest crash log and the one before it, nothing older
        dest.replace(dest.with_name(dest.stem + ".before.txt"))
    dest.write_text("\n\n".join(parts) or "no evidence found", encoding="utf-8")
    STATE.unlink(missing_ok=True)  # the crashed client is gone; the next ensure() launches a fresh one
    return dest, cause


def trim_instance(keep_reports=5, keep_native=3, keep_logs=10):
    """The test instance's own crash reports, JVM crash files, rotated logs and stray harness screenshots stay few
    (the evidence that matters was copied next to the test results)."""
    def keep_newest(files, n):
        for f in sorted(files, key=lambda f: f.stat().st_mtime, reverse=True)[n:]:
            f.unlink(missing_ok=True)
    if (GAME / "crash-reports").exists():
        keep_newest(list((GAME / "crash-reports").glob("*.txt")), keep_reports)
    keep_newest([f for d in (GAME, lw.INSTANCE) for f in d.glob("hs_err_pid*.log")], keep_native)
    keep_newest([f for d in (GAME, lw.INSTANCE) for f in d.glob("replay_pid*.log")], 0)
    if (GAME / "logs").exists():
        keep_newest(list((GAME / "logs").glob("*.log.gz")), keep_logs)
    if SHOTS.exists():
        keep_newest(list(SHOTS.glob("h-*")), 0)  # harness captures are moved into results; leftovers go
        keep_newest([f for f in SHOTS.iterdir() if f.is_file()], 60)
    if PERF.exists():
        keep_newest([f for f in PERF.iterdir() if f.is_file()], 60)


# ----------------------------------------------------------------------------------------------- failure policy
RESET = ["ui close", "perspective first", "try difficulty peaceful", "try effect clear @p"]
DEFAULT_TIMEOUT = 240  # seconds per test; overrunning is a failure, not a hang


def tolerant(lines):
    """A scene's cleanup as best-effort verbs (after a failure, nothing it undoes may be there to undo). Exports
    (`atlaslab ...`) are left out: after a failed capture they are pointless, and a long one (a 1 km view generating its
    chunks) kept the client busy so the next scene failed on a busy queue."""
    return ["try " + l[8:] if l.startswith("command ") else l for l in lines if not l.startswith("atlaslab ")]


def recover(scene=None):
    """After any failure or timeout: cancel what is still queued, run the scene's own cleanup (it never ran) and the
    standard reset, so the next test starts clean. Never raises."""
    try:
        lw.live_actions(["# harness: cancelled after a failure"], timeout=120, cancel=True)
    except Exception:  # noqa: BLE001
        pass
    lines = tolerant((scene or {}).get("cleanup", [])) + RESET
    if "realm" in ((scene or {}).get("pose") or {}):
        lines.append("try nirncraft-realm home")
    try:
        actions(lines, timeout=120)
    except Exception:  # noqa: BLE001
        pass


# ----------------------------------------------------------------------------------------------- window
def ensure_visible():
    """A minimized Minecraft stops rendering (no chunk meshes: every settle times out). Restore the test client's
    window without activating it (SW_SHOWNOACTIVATE: it comes back behind whatever you are working in)."""
    if os.name != "nt":
        return False
    import ctypes
    from ctypes import wintypes
    user32 = ctypes.windll.user32
    pids = {p["pid"] for p in lw.processes()}
    if not pids:
        return False
    found = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def each(hwnd, _):
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value in pids and user32.GetWindowTextLengthW(hwnd) > 0:
            found.append(hwnd)
        return True

    user32.EnumWindows(each, 0)
    restored = False
    for h in found:
        if user32.IsIconic(h):
            user32.ShowWindow(h, 4)  # SW_SHOWNOACTIVATE
            restored = True
    if restored:
        log("  the test client was minimized (it cannot render then): restored behind your windows")
        tr.step("window_restored")
        time.sleep(2)
    return restored


_watch = {"on": False}


def _windows():
    import ctypes
    from ctypes import wintypes
    user32 = ctypes.windll.user32
    pids = {p["pid"] for p in lw.processes()}
    found = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def each(hwnd, _):
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value in pids and user32.GetWindowTextLengthW(hwnd) > 0 and user32.IsWindowVisible(hwnd):
            found.append(hwnd)
        return True

    if pids:
        user32.EnumWindows(each, 0)
    return user32, found


def send_to_back():
    """Behind every other window, without focus: the test runs while you work; bring it forward to watch."""
    if os.name != "nt":
        return
    user32, found = _windows()
    for h in found:
        user32.SetWindowPos(h, 1, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010)  # HWND_BOTTOM, NOSIZE|NOMOVE|NOACTIVATE


def start_watchdog():
    """While the harness runs live tests: every 2 s, a minimized test client is restored behind your windows."""
    if _watch["on"] or os.name != "nt":
        return
    import threading
    _watch["on"] = True

    def loop():
        while _watch["on"]:
            try:
                user32, found = _windows()
                for h in found:
                    if user32.IsIconic(h):
                        user32.ShowWindow(h, 4)  # SW_SHOWNOACTIVATE
                        user32.SetWindowPos(h, 1, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010)
                        tr.step("window_restored")
            except Exception:  # noqa: BLE001
                pass
            time.sleep(2)

    threading.Thread(target=loop, name="harness-window-watchdog", daemon=True).start()


# ----------------------------------------------------------------------------------------------- per-test game log
LOG_FILE = GAME / "logs/latest.log"
NOISE = re.compile(r"(Unable to resolve shader pack option menu element|Error loading class: (mezz|com/simibubi|"
                   r"blusunrize|mekanism|com/direwolf20|com/portingdeadmods)|Missing sound for event|"
                   r"Received passengers for unknown entity)", re.I)
SERIOUS = re.compile(r"(did not parse|Exception|ERROR\]|Unknown live action|Readiness failed|cancelled)", re.I)


def log_mark():
    return LOG_FILE.stat().st_size if LOG_FILE.exists() else 0


def log_since(mark):
    """The game's WARN / ERROR / exception lines written since `mark` (known harmless noise dropped)."""
    if not LOG_FILE.exists():
        return []
    with open(LOG_FILE, "rb") as f:
        size = f.seek(0, 2)
        f.seek(mark if mark <= size else 0)  # the log was rotated (new client): read it all
        text = f.read().decode("utf-8", "replace")
    out = []
    for line in text.splitlines():
        if ("/WARN]" in line or "/ERROR]" in line or "Exception" in line) and not NOISE.search(line):
            out.append(line.strip()[:400])
    return out


def log_summary(lines):
    serious = [l for l in lines if SERIOUS.search(l)]
    return {"logWarnings": len(lines) - len(serious), "logErrors": len(serious)}, serious


# ----------------------------------------------------------------------------------------------- one scene
BLOCKING = {"RaceScreen", "IntroLoadingScreen", "ReceivingLevelScreen", "LevelLoadingScreen", "GenericMessageScreen",
            "DisconnectedScreen", "TitleScreen"}


LOADING = {"IntroLoadingScreen", "ReceivingLevelScreen", "LevelLoadingScreen", "GenericMessageScreen"}


def screen_blocking(limit=600):
    """The screen covering the world when it is one a scene cannot pass (None when the world is showing). While a
    loading screen is up this waits for the game's own hook (status `loading` false / `worldReadyAt` set, logged as
    "world ready"), as long as its heartbeat is alive; `limit` seconds is only a safety net."""
    t0 = time.monotonic()
    seen = None
    while True:
        st = lw.read_json(GAME / "nirncraft-live-status.json") or {}
        s = st.get("screen")
        loading = st.get("loading", s in LOADING)
        if s not in BLOCKING and not loading:
            if seen:
                tr.step("world_ready", after=round(time.monotonic() - t0, 1), was=seen)
            return None
        if s in BLOCKING and s not in LOADING:
            return s
        seen = seen or s or "loading"
        age = time.time() - st.get("updated", 0) / 1000
        if age > lw.HEARTBEAT_STALE:
            return f"{seen} (heartbeat stopped {age:.0f} s ago)"
        if time.monotonic() - t0 > limit:
            return f"{seen} (still loading after {limit} s)"
        time.sleep(0.5)


def timeline_summary(passes):
    """The far field's loading timeline (far-timeline dump: one entry a worker pass since the mark) as metrics: when it
    first showed, when it went idle, the passes' totals per stage, the slowest pass, and the first 300 passes."""
    if not passes:
        return {"farPasses": 0}
    stages = ("cover", "edits", "sections", "update1", "overlays", "update2")
    idle = next((p["t"] for p in passes if not p.get("streaming")), None)
    slow = max(passes, key=lambda p: p["pass"])
    return {"farPasses": len(passes), "farFirstMs": round(passes[0]["t"], 1),
            "farIdleMs": round(idle, 1) if idle is not None else None,
            "farSlowestPassMs": slow["pass"], "farSlowestPassAtMs": round(slow["t"], 1),
            **{f"farTotal_{k}Ms": round(sum(p.get(k, 0) for p in passes), 1) for k in stages},
            "farTimeline": passes[:300]}


def capture(scene, variant, pose_lines, capture_lines):
    """Run one scene + variant; returns (png path, receipt dict, perf dict or None)."""
    tag = f"h-{scene['id']}-{variant}"
    for ext in ("png", "json"):
        (SHOTS / f"{tag}.{ext}").unlink(missing_ok=True)
    blocked = screen_blocking()
    if blocked and scene.get("capture") != "ui":
        raise RuntimeError(f"the client is stuck on {blocked} (the world cannot load behind it); is the installed "
                           f"jar current? (harness.py run --rebuild)")
    ensure_visible()
    mark = log_mark()
    want = variant != "vanilla"
    st = client_state()
    real = (lw.read_json(GAME / "nirncraft-live-status.json") or {}).get("shaders")
    current = real if isinstance(real, bool) else st.get("shaders", True)  # the game's word, not ours
    # the shader toggle settles where the camera is: move to this scene first, then toggle (in one batch)
    toggle = [] if current == want else [f"shaders {'on' if want else 'off'}"]
    lead = ["ui close", "perspective first"]  # nothing left over from an earlier scene (an open map pauses the world)
    try:
        actions(lead + pose_lines + toggle + capture_lines(tag), timeout=scene.get("timeout", DEFAULT_TIMEOUT))
        if toggle:
            st["shaders"] = want
            STATE.write_text(json.dumps(st), encoding="utf-8")
    except RuntimeError as e:
        blocked = screen_blocking()
        recover(scene)  # a failed scene cleans up after itself, then the run moves on
        raise RuntimeError(f"{e} [blocked by {blocked}]" if blocked else str(e)) from None
    png = SHOTS / f"{tag}.png"
    import catalog as _cat
    shots = _cat.shot_files(scene, tag)
    files = [SHOTS / f"{stem}.png" for stem, _ in shots]
    for f in files:
        t0, last = time.monotonic(), -1
        while time.monotonic() - t0 < 60:  # screenshots encode off-thread
            size = f.stat().st_size if f.exists() else -1
            if size > 0 and size == last:
                break
            last = size
            time.sleep(0.4)
        if not f.exists():
            raise RuntimeError(f"no screenshot written for {f.stem}")
    extra = {}
    if len(files) > 1:  # frames / a distance ladder / a pop sweep: one labelled strip, so before/after compares all
        from PIL import Image
        import results as _res
        imgs = [Image.open(f).convert("RGB") for f in files]
        extra = _res.pop_metrics(scene, imgs)
        _res.strip(imgs, [label for _, label in shots]).save(png)
        first_json = SHOTS / f"{shots[0][0]}.json"
        if first_json.exists() and not (SHOTS / f"{tag}.json").exists():
            first_json.replace(SHOTS / f"{tag}.json")
        for f in files:
            f.unlink(missing_ok=True)
            f.with_suffix(".json").unlink(missing_ok=True)
    rec = {}
    if (SHOTS / f"{tag}.json").exists():
        rec = json.loads((SHOTS / f"{tag}.json").read_text(encoding="utf-8"))
    tl = SHOTS / f"{tag}-timeline.json"  # far-timeline dump (a loading scene): its summary and passes travel with it
    if tl.exists():
        try:
            extra.update(timeline_summary(json.loads(tl.read_text(encoding="utf-8"))))
        except (ValueError, OSError) as e:
            extra.setdefault("popFlags", []).append(f"far timeline unreadable: {e}")
        tl.unlink(missing_ok=True)
    lines = log_since(mark)
    if lines:  # every warning and error the game logged during this test travels with its result
        counts, serious = log_summary(lines)
        extra.update(counts)
        extra["gameLog"] = lines[:200]
        for l in serious[:3]:
            extra.setdefault("popFlags", []).append("game log: " + l[l.find("]: ") + 3 if "]: " in l else 0:][:140])
    partial = (lw.read_json(GAME / "nirncraft-live-status.json") or {}).get("settlePartial", 0)
    if partial:  # the world did not finish loading in time: the picture may miss chunks (flagged, not discarded)
        # the game says what was still busy (near chunks, far field and why, picture change, exposure)
        why = next((l.split("(partial): ", 1)[1].strip() for l in reversed(lines or []) if "(partial): " in l), "")
        extra.setdefault("popFlags", []).append(f"partial: {partial} settle(s) timed out" + (f": {why[:160]}" if why
                                                                                              else " (chunks may be missing)"))
        extra["settlePartial"] = partial
        if why:
            extra["settleWhy"] = why
    perf = None
    if scene.get("measure") and (PERF / f"{tag}.json").exists():
        perf = json.loads((PERF / f"{tag}.json").read_text(encoding="utf-8"))
    return png, rec, perf, extra
