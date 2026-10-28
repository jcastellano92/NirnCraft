#!/usr/bin/env python3
"""A play-test session for the owner: the current build in the kept test world, fly and teleport, no autotest.

    py -3 tools/dev/owner_play.py [--lean | --default] [--no-build] [--world NAME]
    py -3 tools/dev/owner_play.py --cheatsheet       # only the commands and places

--lean (default)  the new lean far renderer: ATLAS_FAR_LEAN=1 in the game's environment.   --default  the old far pass.
--no-build        skip the build and install; refuses when the installed core is not the current build.

What it does, in order:
  1. checks nobody else has the client (the instance's java, the live lease, another instance's game);
  2. builds and installs the current core (game.ensure_current: native DLL, jar, atlas-dev / atlas-shaders overrides)
     under the shared lock, label "owner-play: build jar";
  3. starts the session under the shared lock, label "owner play gpu test" (heavy.py: timing benches wait while it runs,
     builds go on). The session closes a leftover Prism (a running Prism forwards the launch and the game would not get
     this process's environment), writes the owner's JVM flags (atlas unified + client generator + passive profiler; the
     autotest, dev and harness flags are left out: no action queue, the player hold on), launches the instance (from a one-shot Task Scheduler task: OUTSIDE heavy.py's 16 GB job, which only waits and watches) straight
     into the kept world (Prism --world), waits for the world, checks the game process's environment block for
     ATLAS_FAR_LEAN, and blocks until no java of the instance has run for 3 minutes in a row (the lock is released then; a relaunch, even the owner's own from Prism, stays covered), puts the instance's flags back and
     closes the Prism it started (its environment must not reach the next harness launch);
  4. prints the evidence and the cheat sheet.
State: E:/NirnCraft-scratch/owner-play/state.json, log session.log. Outputs stay off C:.
"""
import argparse
import ctypes
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = Path(os.environ.get("NIRNCRAFT_SCRATCH", "E:/NirnCraft-scratch"))
HEAVY = SCRATCH / "locks/heavy.py"
OUT = SCRATCH / "owner-play"
STATE = OUT / "state.json"
SESSION_LOG = OUT / "session.log"
LEASE_OWNER = "owner-play"
READY_LIMIT_S = 900
START_S = 180  # Prism may take this long to start the game process
GRACE_S = 180  # the lock outlives the client by this much (relaunches, crash and restart)
SESSION_LABEL = "owner play gpu test"  # "gpu test": heavy.py makes timing benches wait for it
BUILD_LABEL = "owner-play: build jar"
# The owner's flags: Infinite ATLAS draws every pixel with the client's GPU world generation and the passive profiler
# (the harness group "atlas" minus its autotest world). Nothing under nirncraft.autotest / dev / harness.
OWNER_JVM = {"nirncraft.atlas.unified": "true", "nirncraft.atlas.clientGen": "true", "nirncraft.atlas.profile": "true",
             "nirncraft.atlas.safeMode": "false", "nirncraft.devTest": "true"}
DROP_FLAGS = re.compile(r"-Dnirncraft\.(autotest|dev|preview|harness|visualtest|atlas)[\w.]*(=\S*)?")
TAMRIEL = ROOT / "mod/src/main/resources/nirncraft/tamriel"


def say(msg):
    print(msg, flush=True)


# ----------------------------------------------------------------------------------------------- cheat sheet
def _json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def _ground(x, z):
    """The macro ground height at a block (elevation.png: 16-bit, 64 per block), or None without Pillow."""
    try:
        from PIL import Image
        im = _ground.__dict__.setdefault("im", Image.open(TAMRIEL / "elevation.png"))
        return im.getpixel((int((x + 15360) // 32), int((z + 5120) // 32))) / 64
    except Exception:  # noqa: BLE001 (no Pillow, a missing raster: fixed heights below)
        return None


def spots():
    """[(name, x, y, z, note)] from the repo's data: places (skyrim_settlements / skyrim_landmarks), peaks.json, the
    lake and coast cells of the map rasters (tamriel_vectors.json lake_ilinalta; features.png's lake flag). y is the
    ground plus a flying margin, capped under the world's top (y 639)."""
    places = {p["id"]: p for f in ("skyrim_settlements", "skyrim_landmarks") for p in _json(TAMRIEL / "places" / f"{f}.json")}
    peaks = _json(TAMRIEL / "peaks.json")["peaks"]
    peak = next(p for p in peaks if p["name"] == "throat_of_the_world")
    ledge = next(sh for sh in peak["shelves"] if (sh[0], sh[1]) == (places["high_hrothgar"]["x"], places["high_hrothgar"]["z"]))
    plan = [  # (label, place id, lift above the ground, note)
        ("Whiterun", "whiterun", 55, "capital, the plains"),
        ("Riverwood", "riverwood", 50, "town on the White River"),
        ("Helgen", "helgen", 50, "town, Falkreath hold"),
        ("Falkreath", "falkreath", 55, "capital, pine forest"),
        ("Solitude", "solitude", 70, "capital on the north-west coast"),
    ]
    out = []
    for label, pid, lift, note in plan:
        p = places[pid]
        g = _ground(p["x"], p["z"])
        y = min(639, round((max(g, 63) if g else 100) + lift))
        out.append((label, p["x"], y, p["z"], note))
    # High Hrothgar stands on a ledge (peaks.json shelves: x, z, radius, y), far above the raster's macro ground
    out.append(("High Hrothgar", ledge[0], round(ledge[3]) + 12, ledge[1], f"the ledge, y {ledge[3]:.0f}"))
    out.append(("Throat of the World (mountain)", peak["x"], min(639, peak["height"] + 40), peak["z"],
                f"summit about y {peak['height']}; the highest peak"))
    out.append(("Lake Ilinalta (lake)", -464, 190, 752, "lake_ilinalta polygon, features.png lake cell; fly low over the water"))
    # coast: the cells between Dawnstar's shore (ground 72 at z -1952) and the sea (ground 59 at z -2144)
    out.append(("Dawnstar coast (coast)", 174, 110, -1990, "shore of the Sea of Ghosts, north of Dawnstar"))
    out.append(("High aerial over Whiterun", places["whiterun"]["x"], 560, places["whiterun"]["z"], "near the build ceiling (y 639)"))
    return out


def cheatsheet():
    s = spots()
    lines = [
        "CHEAT SHEET (the kept test world has cheats on)",
        "  fly:        creative: double-tap SPACE to fly (SPACE up, SHIFT down).  /gamemode spectator for noclip, /gamemode creative back",
        "  teleport:   /tp @s <x> <y> <z>        (look direction too: /tp @s <x> <y> <z> <yaw> <pitch>, pitch 90 = straight down)",
        "  time/sky:   /time set day   /time set noon   /weather clear",
        "  tour:       /nirncraft-tour start towns|cities|landmarks|forts|dungeons|all   [limit:5] [dwell:20] [shot:aerial]",
        "              then  next | back | goto <n> | pause | resume | dwell <s> | status | stop   (stop gives your position back)",
        "  after a teleport wait 5-20 s for the far field to build before judging what you see.",
        "  places (x y z, y is a flying height; the world is y -64..639):",
    ]
    for name, x, y, z, note in s:
        lines.append(f"    {name:<32} /tp @s {x} {y} {z}    # {note}")
    lines.append("    looking down: add yaw and pitch, e.g. /tp @s 62 560 -68 0 90 (straight down), or /tp @s 62 400 150 180 50 (Whiterun from the south, oblique)")
    lines.append("  END: Esc > Save and Quit to Title > Quit Game (closing the game window also works); the GPU lock is released 3 minutes after the game is gone.")
    lines.append("  passive profiler log: <instance>/.minecraft/atlas-profile.log (every 2 s)")
    return "\n".join(lines)


# ----------------------------------------------------------------------------------------------- processes
def ps(script):
    r = subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True)
    return r.stdout.strip()


def prism_pids():
    out = ps("@(Get-Process -Name prismlauncher -ErrorAction SilentlyContinue | ForEach-Object { $_.Id }) | ConvertTo-Json -Compress")
    v = json.loads(out) if out else []
    return v if isinstance(v, list) else [v]


def other_games(instance_dir):
    """Game processes of other Prism instances (never touched: we refuse to start beside them)."""
    pat = r"PrismLauncher[/\\]+instances[/\\]+"
    out = ps("@(Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^javaw?\\.exe$' -and "
             f"$_.CommandLine -match '{pat}' }} | ForEach-Object {{ [pscustomobject]@{{pid=$_.ProcessId; cl=$_.CommandLine}} }}) | ConvertTo-Json -Compress")
    rows = json.loads(out) if out else []
    rows = rows if isinstance(rows, list) else [rows]
    mine = Path(instance_dir).as_posix().lower()
    return [r["pid"] for r in rows if mine not in r["cl"].replace("\\", "/").lower()]


def process_env(pid):
    """The environment block of a process of this user (PEB walk): the proof that a variable reached the game."""
    k32, nt = ctypes.WinDLL("kernel32", use_last_error=True), ctypes.WinDLL("ntdll")
    k32.OpenProcess.restype = ctypes.c_void_p
    k32.OpenProcess.argtypes = [ctypes.c_uint32, ctypes.c_int, ctypes.c_uint32]
    k32.ReadProcessMemory.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)]
    k32.CloseHandle.argtypes = [ctypes.c_void_p]
    nt.NtQueryInformationProcess.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(ctypes.c_ulong)]
    h = k32.OpenProcess(0x0410, False, pid)  # QUERY_INFORMATION | VM_READ
    if not h:
        return {}

    def read(addr, n):
        buf, got = ctypes.create_string_buffer(n), ctypes.c_size_t()
        if not k32.ReadProcessMemory(h, ctypes.c_void_p(addr), buf, n, ctypes.byref(got)):
            return b""
        return buf.raw[:got.value]

    try:
        pbi = (ctypes.c_void_p * 6)()
        if nt.NtQueryInformationProcess(h, 0, pbi, ctypes.sizeof(pbi), ctypes.byref(ctypes.c_ulong())):
            return {}
        params = int.from_bytes(read(pbi[1] + 0x20, 8), "little")  # PEB.ProcessParameters (x64)
        env = int.from_bytes(read(params + 0x80, 8), "little")  # .Environment
        size = int.from_bytes(read(params + 0x3F0, 8), "little")  # .EnvironmentSize
        raw = read(env, min(size or 1 << 16, 1 << 20))
    finally:
        k32.CloseHandle(h)
    out = {}
    for item in raw.decode("utf-16-le", "ignore").split("\0"):
        if not item:
            break
        k, _, v = item.partition("=")
        if k:
            out[k] = v
    return out


def java_has_flag(pid, text):
    """Whether the process's command line contains text (never printed: launcher command lines hold credentials)."""
    out = ps(f"(Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}').CommandLine")
    return text in out


def close_prism(wait_s=40):
    """Closes Prism like its window's X (nothing is killed unless it ignores that), only when no game of ours or another
    instance runs. True when no Prism remains."""
    for pid in prism_pids():
        ps(f"$p = Get-Process -Id {pid} -ErrorAction SilentlyContinue; if ($p) {{ [void]$p.CloseMainWindow() }}")
    t0 = time.monotonic()
    while prism_pids() and time.monotonic() - t0 < wait_s:
        time.sleep(1)
    left = prism_pids()
    if left:
        say(f"  Prism ({left}) ignored the close request; stopping it (no game runs)")
        for pid in left:
            ps(f"Stop-Process -Id {pid} -Force -ErrorAction SilentlyContinue")
        time.sleep(2)
    return not prism_pids()


def preflight(lw, game):
    if lw.processes():
        raise SystemExit(f"the {lw.INSTANCE_NAME} client is already running (pid {[p['pid'] for p in lw.processes()]}): "
                         "someone is using it; nothing was changed")
    held = game.lease_holder()
    if held and held.get("owner") != LEASE_OWNER:
        raise SystemExit(f"the live lease is held by {held.get('owner')}: wait or ask its owner")
    others = other_games(lw.INSTANCE)
    if others:
        raise SystemExit(f"another Prism instance's game is running (pid {others}); close it first (not touched)")


# ----------------------------------------------------------------------------------------------- core check
def verify_core(game):
    """Problems that mean the instance would not run the current atlas-render build (empty: all good)."""
    import native_sources
    problems = []
    inst = game.GAME / "mods/nirncraft-core.jar"
    built = game.NATIVE_BUILT
    digest = native_sources.source_hash()
    if not built.exists():
        problems.append(f"native build missing: {built}")
    elif game.native_stale(digest=digest):
        problems.append("native sources are newer than native/build/Release/atlas_core.dll")
    else:
        t = subprocess.run(["git", "-C", str(ROOT), "log", "-1", "--format=%ct", "--", "native"], capture_output=True, text=True).stdout.strip()
        if t.isdigit() and built.stat().st_mtime < int(t) - 1 and not (built.with_suffix(".sources").exists()
                                                                        and built.with_suffix(".sources").read_text().strip() == digest):
            problems.append("atlas_core.dll is older than the last commit touching native/")
    if not inst.exists():
        problems.append("no installed core")
    else:
        # (install-core keeps the runtime's verified bakes, so the bytes differ from the build's: compare the entries)
        try:
            if game.BUILT.exists():
                with zipfile.ZipFile(game.BUILT) as b, zipfile.ZipFile(inst) as i:
                    want = {e.filename: e.CRC for e in b.infolist() if not e.is_dir()}
                    have = {e.filename: e.CRC for e in i.infolist() if not e.is_dir()}
                diff = sorted(n for n in want if have.get(n) != want[n] and not n.startswith("nirncraft/baked/"))
                if diff:
                    problems.append(f"installed core differs from the latest build in {len(diff)} entries (first: {diff[0]})")
        except (OSError, zipfile.BadZipFile) as e:
            problems.append(f"cannot compare the installed core with the build: {e}")
        try:
            with zipfile.ZipFile(inst) as z:
                dll = z.read("natives/windows-x86_64/atlas_core.dll")
            import hashlib
            if built.exists() and hashlib.sha256(dll).hexdigest()[:16] != game.sha(built):
                problems.append("the installed core jar carries another atlas_core.dll than native/build/Release")
        except (KeyError, OSError, zipfile.BadZipFile) as e:
            problems.append(f"cannot read the installed core's native library: {e}")
    dev = game.GAME / "atlas-dev" / game.NATIVE_SHIPPED.name
    if dev.exists() and built.exists() and game.sha(dev) != game.sha(built):
        problems.append("atlas-dev/atlas_core.dll overrides the jar's core with another build")
    sh = game.GAME / "atlas-shaders"
    if sh.is_dir() and any(sh.glob("*.spv")):
        problems.append("atlas-shaders/ holds kernels that shadow the core's own")
    return problems


# ----------------------------------------------------------------------------------------------- instance flags
def write_owner_flags(lw, game):
    """JvmArgs without the autotest / dev / harness / atlas flags, plus the owner's; and the flags file the mod reads.
    Returns the originals for put_back."""
    cfg = lw.INSTANCE / "instance.cfg"
    text = cfg.read_text(encoding="utf-8")
    flags_file = game.FLAGS_FILE
    orig = {"cfg": text, "flags": flags_file.read_bytes() if flags_file.exists() else None}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "instance.cfg.orig").write_text(text, encoding="utf-8")
    m = re.search(r'^JvmArgs="?(.*?)"?$', text, re.M)
    if not m:
        raise SystemExit("instance has no JvmArgs")
    base = DROP_FLAGS.sub("", m.group(1))
    base = re.sub(r"\s+", " ", base).strip()
    args = f"{base} " + " ".join(f"-D{k}={v}" for k, v in sorted(OWNER_JVM.items()))
    lw.atomic(cfg, (text[:m.start()] + f'JvmArgs="{args}"' + text[m.end():]).encode())
    # the file: only the owner's flags, no harness word (the mod applies it when the JVM has no such property)
    lw.atomic(flags_file, ("\n".join(f"{k}={v}" for k, v in sorted(OWNER_JVM.items())) + "\n").encode())
    return orig


def put_back(lw, game, orig):
    if not orig:
        return
    lw.atomic(lw.INSTANCE / "instance.cfg", orig["cfg"].encode())
    if orig["flags"] is None:
        game.FLAGS_FILE.unlink(missing_ok=True)
    else:
        lw.atomic(game.FLAGS_FILE, orig["flags"])


# ----------------------------------------------------------------------------------------------- state
def write_state(**kw):
    OUT.mkdir(parents=True, exist_ok=True)
    try:
        cur = json.loads(STATE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        cur = {}
    cur.update(kw, updated=time.time())
    STATE.write_text(json.dumps(cur, indent=1), encoding="utf-8")


def read_state():
    try:
        return json.loads(STATE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


# ----------------------------------------------------------------------------------------------- the jobs run under heavy.py
def import_harness():
    sys.path[:0] = [str(ROOT / "tools/harness"), str(ROOT / "tools/dev"), str(ROOT / "tools")]
    import live_world as lw
    import game
    return lw, game


def job_build():
    lw, game = import_harness()
    preflight(lw, game)
    print("TRACE begin owner-play-build", flush=True)
    game.ensure_current()
    problems = verify_core(game)
    print("TRACE done owner-play-build", flush=True)
    if problems:
        raise SystemExit("the build is not current: " + "; ".join(problems))
    print("core current: installed jar, native DLL, atlas-dev and atlas-shaders checked", flush=True)


TASK = "NirnCraftOwnerPlayLaunch"


def launch_outside(lw, lean, world):
    """Starts Prism and the game OUTSIDE heavy.py's job: the lock's command only waits and watches. A child of this
    process would sit in heavy's memory-capped job object (16 GB; the game peaks at 12-14.5 GB, and allocations past the
    cap fail). A one-shot Task Scheduler task (interactive, as this user) is a child of the scheduler instead, with
    the environment of a fresh logon plus what the .cmd sets: ATLAS_* cleared, ATLAS_FAR_LEAN=1 for --lean."""
    cmd = OUT / "launch.cmd"
    lines = ["@echo off"]
    lines += [f"set {k}=" for k in sorted(os.environ) if k.upper().startswith("ATLAS_")]  # (user-level leftovers)
    lines += ["set NIRNCRAFT_HARNESS_JVM="]
    if lean:
        lines.append("set ATLAS_FAR_LEAN=1")
    lines.append(f"set NIRNCRAFT_LAUNCH_WORLD={world}")
    lines.append(f'"{sys.executable}" "{ROOT / "tools/dev/live_world.py"}" launch > "{OUT / "launch.out"}" 2>&1')
    cmd.write_text("\r\n".join(lines) + "\r\n", encoding="ascii")
    def sch(*a):
        return subprocess.run(["schtasks", *a], capture_output=True, text=True)
    r = sch("/create", "/tn", TASK, "/tr", str(cmd), "/sc", "once", "/st", "23:59", "/it", "/f")
    if r.returncode:
        raise SystemExit("could not create the launch task: " + (r.stdout + r.stderr)[-400:])
    r = sch("/run", "/tn", TASK)
    if r.returncode:
        raise SystemExit("could not run the launch task: " + (r.stdout + r.stderr)[-400:])


def drop_launch_task():
    subprocess.run(["schtasks", "/delete", "/tn", TASK, "/f"], capture_output=True, text=True)


def log_text(game):
    try:
        return (game.GAME / "logs/latest.log").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def job_session(lean, world):
    lw, game = import_harness()
    t0 = time.time()
    orig = None
    try:
        write_state(phase="starting", lean=lean, world=world, pid=None, started=t0, error=None, ended=None)
        preflight(lw, game)
        problems = verify_core(game)
        if problems:
            raise SystemExit("the installed core is not the current build (" + "; ".join(problems) + "); run without --no-build")
        if not (game.GAME / "saves" / world / "level.dat").exists():
            raise SystemExit(f"world {world} not found in {game.GAME / 'saves'}")
        game.take_lease(owner=LEASE_OWNER)
        if prism_pids():
            say("closing the idle Prism so the game gets this process's environment")
            if not close_prism():
                raise SystemExit("Prism would not close")
        orig = write_owner_flags(lw, game)
        write_state(phase="launching")
        say(f"launching {lw.INSTANCE_NAME} into {world}  (ATLAS_FAR_LEAN={'1' if lean else 'unset'}) outside the lock's job")
        launch_outside(lw, lean, world)
        # The lock is held until NO java of this instance has been there for GRACE_S in a row: a relaunch (the owner's own,
        # from Prism), a crash and restart, a menu-to-world round trip stay covered. Every java of the instance counts,
        # whichever pid (lw.processes() matches the instance's folder in the command line).
        pids, pid, ready_at, last_beat, gone_since, launch_t, slow, seen = set(), None, None, 0, None, time.time(), False, False
        while True:
            now = time.time()
            procs = lw.processes()
            cur = {p["pid"] for p in procs}
            if cur:
                gone_since, seen = None, True
                new = cur - pids
                if new:  # a first launch, or another one (a relaunch): its environment and readiness are checked afresh
                    pid = max(new)
                    if pids:
                        say(f"another game process appeared (pid {pid}); still holding the lock")
                        launch_t, ready_at, slow = now, None, False
                        write_state(ready=False, phase="loading")
                    write_state(pid=pid, phase="loading")
                pids = cur
            elif not seen and now - launch_t < START_S:
                pass  # (Prism is still starting the game)
            else:
                if gone_since is None:
                    gone_since = now
                    say(f"no game process; holding the lock for {GRACE_S // 60} more minutes in case it comes back")
                    write_state(phase="gone", ready=False)
                    if ready_at is None:
                        write_state(error="the game exited before the world was ready; see logs/latest.log")
                if now - gone_since >= GRACE_S:
                    break
                pids, ready_at = set(), None  # (a process that comes back is a new launch)
            if cur:
                log = game.GAME / "logs/latest.log"
                text = log_text(game) if log.exists() and log.stat().st_mtime >= launch_t else ""
                loaded = "atlas loading phase: ready" in text or "Time from main menu to in-game" in text
                if ready_at is None and ("logged in with entity id" in text or loaded):  # the player is in the world
                    ready_at = now
                    envvars = process_env(pid) if pid else {}
                    got = envvars.get("ATLAS_FAR_LEAN")
                    ev = {"envAtlasFarLean": got, "leanEnvArrived": got == "1" if lean else got is None,
                          "autotestFlagsInJava": java_has_flag(pid, "nirncraft.autotest") if pid else None,
                          "readySeconds": round(now - launch_t, 1)}
                    write_state(phase="playing", ready=True, error=None, loadingReady=loaded, **ev)
                    say("TRACE done owner-play-joined " + json.dumps(ev))
                elif ready_at is not None and loaded and not read_state().get("loadingReady"):
                    write_state(loadingReady=True, loadedSeconds=round(now - launch_t, 1))
                    say("TRACE done owner-play-loaded")
                elif ready_at is None and now - launch_t > READY_LIMIT_S and not slow:
                    slow = True  # (the lock stays: the client is running, the owner may still use it)
                    write_state(error=f"world not joined after {READY_LIMIT_S} s")
            if now - last_beat > 60:  # progress for the lock's stall watch, and the live lease stays fresh
                last_beat = now
                game.take_lease(owner=LEASE_OWNER)
                say(f"TRACE progress owner-play {int((now - t0) / 60)} min, pids {sorted(pids)}, "
                    f"{'playing' if ready_at else 'gone' if gone_since else 'loading'}")
            time.sleep(3)
        say("no game process for the whole grace period; releasing the lock")
        write_state(phase="ended", ended=time.time())
    except SystemExit as e:
        write_state(phase="failed", error=str(e), ended=time.time())
        raise
    except BaseException as e:  # noqa: BLE001
        write_state(phase="failed", error=repr(e), ended=time.time())
        raise
    finally:
        # Prism, started by the launch above, may linger (console window): its environment would reach the next
        # launch, so it goes with the game; then the instance's flags are put back (Prism rewrites instance.cfg on exit)
        if not lw.processes():
            close_prism()
        if orig:
            put_back(lw, game, orig)
        drop_launch_task()
        if (game.lease_holder() or {}).get("owner") == LEASE_OWNER:
            Path(game.LEASE).unlink(missing_ok=True)


# ----------------------------------------------------------------------------------------------- the entry point
def heavy(label, args):
    """The command under the shared lock (its job caps memory at 16 GB: the game is never launched inside it)."""
    return ["py", "-3", str(HEAVY), label, "--", "py", "-3", str(Path(__file__).resolve()), *args]


def evidence(lw_game):
    lw, game = lw_game
    st = read_state()
    verdict = ("ARRIVED" if st.get("lean") else "absent, as asked (--default)") if st.get("leanEnvArrived") else "WRONG (not as asked)"
    lines = [f"session: {st.get('phase')}  pid {st.get('pid')}  ready in {st.get('readySeconds')} s",
             f"ATLAS_FAR_LEAN in the game process's environment: {st.get('envAtlasFarLean')!r}  ->  {verdict}",
             f"autotest flags on the game's command line: {st.get('autotestFlagsInJava')}  (must be False)"]
    if st.get("loadingReady"):
        lines.append(f"loading phase: finished ({st.get('loadedSeconds', st.get('readySeconds'))} s); the player is free")
    else:
        waits = re.findall(r"atlas loading phase: ([\d.]+) s, waiting for: ([^\r\n]*)", log_text(game))
        lines.append("LOADING SCREEN STILL HOLDING THE PLAYER: " + (f"{waits[-1][1]} (after {waits[-1][0]} s)" if waits else "?")
                     + ". The screen offers 'Enter anyway' after 180 s: click it (nothing else is needed).")
    prof = game.GAME / "atlas-profile.log"
    try:
        tail = prof.read_text(encoding="utf-8", errors="replace")[-200000:]
        lines.append(f"profiler log: {prof.stat().st_size // 1024} KB, 'farTrace' pass in it: {'farTrace' in tail}")
    except OSError:
        lines.append("profiler log: not written yet")
    return lines


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--lean", action="store_true", help="the lean far renderer (ATLAS_FAR_LEAN=1); the default")
    g.add_argument("--default", action="store_true", help="the old far pass (no ATLAS_FAR_LEAN)")
    ap.add_argument("--no-build", action="store_true", help="skip the build and install (refuses when the core is not current)")
    ap.add_argument("--world", help="saved world to join (default: the harness's kept world for this world generation)")
    ap.add_argument("--cheatsheet", action="store_true")
    ap.add_argument("--timeout", type=int, default=30 * 60, help="seconds to wait for the world (the lock may queue behind builds)")
    ap.add_argument("--_job", choices=["build", "session"], help=argparse.SUPPRESS)
    a = ap.parse_args()
    lean = not a.default
    if a.cheatsheet:
        say(cheatsheet())
        return
    if a._job == "build":
        return job_build()
    if a._job == "session":
        return job_session(lean, a.world)
    if os.name != "nt":
        raise SystemExit("Windows only (Prism, the Vulkan core)")
    lw, game = import_harness()
    preflight(lw, game)
    st = read_state()
    if st.get("phase") in ("starting", "launching", "loading", "playing", "gone") and time.time() - st.get("updated", 0) < 180:
        raise SystemExit(f"an owner-play session is already running ({st.get('phase')}, state {STATE}); it holds the lock until "
                         "the game has been gone for 3 minutes")
    world = a.world
    if not world:
        import cameras
        world = f"harness_{cameras.worldgen_hash()}"  # (not game.kept_world(): that also deletes older worlds)
    if not (game.GAME / "saves" / world / "level.dat").exists():
        raise SystemExit(f"world {world} does not exist in {game.GAME / 'saves'}; pass --world NAME (an existing save)")
    OUT.mkdir(parents=True, exist_ok=True)
    if not a.no_build:
        say(f"building and installing the current core under the lock ({BUILD_LABEL}) ...")
        r = subprocess.run(heavy(BUILD_LABEL, ["--_job", "build"]))
        if r.returncode:
            raise SystemExit(f"build step failed (rc {r.returncode}); no session was started")
    else:
        problems = verify_core(game)
        if problems:
            raise SystemExit("--no-build, but the core is not current: " + "; ".join(problems))
    write_state(phase="queued", ready=False, lean=lean, world=world, error=None)
    cmd = heavy(SESSION_LABEL, ["--_job", "session", "--world", world] + ([] if lean else ["--default"]))
    log = open(SESSION_LOG, "w", encoding="utf-8")
    flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
    subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, creationflags=flags, close_fds=True)
    say(f"session started under the lock ({SESSION_LABEL!r}); waiting for the world (log {SESSION_LOG}) ...")
    t0 = time.time()
    while time.time() - t0 < a.timeout:
        st = read_state()
        if st.get("phase") == "failed" or (st.get("phase") == "gone" and not st.get("ready")):
            raise SystemExit("session failed: " + str(st.get("error")))
        if st.get("ready"):
            break
        time.sleep(3)
    else:
        raise SystemExit(f"world not ready after {a.timeout} s; see {SESSION_LOG}")
    t1 = time.time()
    while time.time() - t1 < 150 and not read_state().get("loadingReady"):
        time.sleep(3)
    say("")
    for line in evidence((lw, game)):
        say(line)
    say(f"world {world} ({'lean' if lean else 'default'} far renderer); close the game to end the session; the lock is released 3 minutes after the last game process is gone")
    say("")
    say(cheatsheet())


if __name__ == "__main__":
    main()
