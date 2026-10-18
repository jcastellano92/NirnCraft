"""A disposable full-pack dedicated server for multiplayer (TCP) tests of the client: the far-field tile stream,
packets, codecs and visuals as a remote player sees them.

    py -3 tools/dev/mp_server.py setup --zip E:/.../Skycraft-26.3-server-<build>.zip [--port 25790]
    py -3 tools/dev/mp_server.py start            # detached; returns when "Done" appears (or fails)
    py -3 tools/dev/mp_server.py cmd "op jsnoop420" "tp jsnoop420 0 300 0"
    py -3 tools/dev/mp_server.py jar PATH          # swap in a rebuilt core jar (server must be stopped)
    py -3 tools/dev/mp_server.py status | log [N] | stop

Then aim the client at it: tools/qa/prism_autotest.ps1 -PrepareOnly -Live -SourceZip dist/...zip
-Server 127.0.0.1:<port> -Java <java25>, and run live_world.py launch / status (remoteConnected) / actions.

The copy lives in E:/Skycraft-scratch/migration-26.3/mp-server (SKYCRAFT_MP_DIR overrides): loopback only, offline
mode, no whitelist, never pauses when empty; the EULA is accepted for this disposable test copy only. A small bridge
process owns the server's console: commands are appended to console.in, `stop` is always a clean console stop, and
no process is ever killed by name.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(os.environ.get("SKYCRAFT_MP_DIR", "E:/Skycraft-scratch/migration-26.3/mp-server"))
SERVER = ROOT / "server"
JAVA = Path(os.environ.get("SKYCRAFT_JAVA", "E:/Skycraft-scratch/toolchains/jdk-25.0.4.1+1/bin/java.exe"))
CONSOLE, OUT, STATE = SERVER / "console.in", SERVER / "server.out", ROOT / "mp-server.json"


def state():
    return json.loads(STATE.read_text()) if STATE.exists() else {}


def alive(pid):
    if not pid:
        return False
    r = subprocess.run(["tasklist", "/FI", f"PID eq {int(pid)}", "/FO", "CSV", "/NH"], capture_output=True, text=True)
    return str(int(pid)) in r.stdout


def setup(zip_path, port):
    if alive(state().get("java")):
        sys.exit("the test server is running; stop it first")
    if ROOT.exists():
        shutil.rmtree(ROOT)
    ROOT.mkdir(parents=True)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(ROOT)
    props = SERVER / "server.properties"
    s = props.read_text(encoding="utf-8")
    for k, v in {"online-mode": "false", "white-list": "false", "enforce-whitelist": "false", "server-port": str(port),
                 "server-ip": "127.0.0.1", "pause-when-empty-seconds": "-1"}.items():
        s = re.sub(rf"^{re.escape(k)}=.*$", f"{k}={v}", s, flags=re.M) if re.search(rf"^{re.escape(k)}=", s, re.M) \
            else s + f"\n{k}={v}"
    props.write_text(s, encoding="utf-8")
    (SERVER / "eula.txt").write_text("eula=true\n")  # disposable loopback test copy only
    STATE.write_text(json.dumps({"port": port, "zip": str(zip_path)}))
    print(f"test server set up in {SERVER} on 127.0.0.1:{port}")


def bridge():
    """Detached: run the server with a piped console fed from console.in."""
    args = ["@user_jvm_args.txt", "@libraries/net/neoforged/neoforge/26.3.0.90/win_args.txt", "nogui"]
    with open(OUT, "wb") as out:
        p = subprocess.Popen([str(JAVA), *args], cwd=SERVER, stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT)
        st = state()
        st.update(java=p.pid, bridge=os.getpid())
        STATE.write_text(json.dumps(st))
        pos = CONSOLE.stat().st_size if CONSOLE.exists() else 0
        while p.poll() is None:
            if CONSOLE.exists() and CONSOLE.stat().st_size > pos:
                with open(CONSOLE, "rb") as f:
                    f.seek(pos)
                    data = f.read()
                pos += len(data)
                try:
                    p.stdin.write(data)
                    p.stdin.flush()
                except OSError:
                    break
            time.sleep(0.3)


def start(timeout=900):
    st = state()
    if not st:
        sys.exit("run setup first")
    if alive(st.get("java")):
        print("already running")
        return
    CONSOLE.write_bytes(b"")
    flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
    subprocess.Popen([sys.executable, __file__, "_bridge"], creationflags=flags, close_fds=True)
    t0 = time.monotonic()
    while time.monotonic() - t0 < timeout:
        text = OUT.read_text(encoding="utf-8", errors="replace") if OUT.exists() else ""
        if "Done (" in text:
            print(f"server ready on 127.0.0.1:{st['port']} after {time.monotonic() - t0:.0f} s")
            for line in text.splitlines():
                if "aether tile service" in line:
                    print("  " + line.strip()[-140:])
            return
        if "crash report" in text.lower() or (time.monotonic() - t0 > 30 and not alive(state().get("java"))):
            sys.exit("server failed to start; see " + str(OUT))
        time.sleep(2)
    sys.exit("server did not report Done in time; it is left running for inspection (stop it with `stop`)")


def cmd(lines):
    if not alive(state().get("java")):
        sys.exit("the test server is not running")
    with open(CONSOLE, "ab") as f:
        for line in lines:
            f.write((line + "\n").encode())
    time.sleep(1.5)
    print("\n".join(OUT.read_text(encoding="utf-8", errors="replace").splitlines()[-len(lines) * 2:]))


def stop(timeout=180):
    st = state()
    if not alive(st.get("java")):
        print("not running")
        return
    with open(CONSOLE, "ab") as f:
        f.write(b"stop\n")
    t0 = time.monotonic()
    while alive(st.get("java")) and time.monotonic() - t0 < timeout:
        time.sleep(1)
    if alive(st.get("java")):
        sys.exit("clean stop still pending; nothing was killed")
    saved = "All dimensions are saved" in OUT.read_text(encoding="utf-8", errors="replace")
    print("stopped cleanly" + (" (all dimensions saved)" if saved else ""))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="action", required=True)
    s = sub.add_parser("setup")
    s.add_argument("--zip", required=True)
    s.add_argument("--port", type=int, default=25790)
    sub.add_parser("start")
    c = sub.add_parser("cmd")
    c.add_argument("lines", nargs="+")
    j = sub.add_parser("jar")
    j.add_argument("path")
    sub.add_parser("status")
    lg = sub.add_parser("log")
    lg.add_argument("n", nargs="?", type=int, default=20)
    sub.add_parser("stop")
    sub.add_parser("_bridge")
    a = ap.parse_args()
    if a.action == "setup":
        setup(a.zip, a.port)
    elif a.action == "start":
        start()
    elif a.action == "cmd":
        cmd(a.lines)
    elif a.action == "jar":
        if alive(state().get("java")):
            sys.exit("stop the server first")
        shutil.copyfile(a.path, SERVER / "mods/skycraft-core.jar")
        print("core jar replaced")
    elif a.action == "status":
        st = state()
        print(json.dumps({**st, "running": alive(st.get("java"))}))
    elif a.action == "log":
        print("\n".join(OUT.read_text(encoding="utf-8", errors="replace").splitlines()[-a.n:]))
    elif a.action == "stop":
        stop()
    elif a.action == "_bridge":
        bridge()


if __name__ == "__main__":
    main()
