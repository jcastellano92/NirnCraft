"""Drives a dedicated test server: starts `gradlew runServer`, waits until the world is up, sends console commands
over RCON (Gradle does not forward stdin to the game), waits for each command's expected output, then stops the
server. Used to run the density audit and smoke tests.

Usage: py -3 tools/explore/server_session.py [--fresh] [--seed N] [--timeout S] "command::wait-for-regex" ...
The regex is matched against the RCON reply first, then against the server log as it streams (for commands that
finish on a background thread, like the audit). A command without '::' just prints its reply.
"""
import os
import queue
import re
import socket
import struct
import subprocess
import sys
import threading
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MOD = os.path.join(ROOT, "mod")
RUN = os.path.join(MOD, "run")
RCON_PORT, RCON_PASS = 25612, "nirncraft-explore"


class Rcon:
    def __init__(self, port, password):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=600)
        self.id = 0
        self.send(3, password)

    def send(self, kind, body):
        self.id += 1
        data = struct.pack("<ii", self.id, kind) + body.encode("utf-8") + b"\x00\x00"
        self.s.sendall(struct.pack("<i", len(data)) + data)
        return self.read()

    def read(self):
        raw = self._exact(4)
        n = struct.unpack("<i", raw)[0]
        data = self._exact(n)
        return data[8:-2].decode("utf-8", "replace")

    def _exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.s.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("rcon closed")
            buf += chunk
        return buf

    def command(self, cmd):
        return self.send(2, cmd)


def main():
    args = sys.argv[1:]
    fresh = "--fresh" in args
    seed = "1234"
    timeout = 3600
    cmds = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--fresh":
            pass
        elif a == "--seed":
            seed = args[i + 1]
            i += 1
        elif a == "--timeout":
            timeout = int(args[i + 1])
            i += 1
        else:
            cmds.append(a)
        i += 1
    os.makedirs(RUN, exist_ok=True)
    with open(os.path.join(RUN, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    props = os.path.join(RUN, "server.properties")
    lines = {}
    if os.path.exists(props):
        for ln in open(props, encoding="utf-8"):
            if "=" in ln and not ln.startswith("#"):
                k, v = ln.rstrip("\n").split("=", 1)
                lines[k] = v
    lines.update({"level-seed": seed, "online-mode": "false", "spawn-protection": "0", "max-tick-time": "-1",
                  "sync-chunk-writes": "false", "server-port": "25611", "enable-rcon": "true",
                  "rcon.port": str(RCON_PORT), "rcon.password": RCON_PASS, "broadcast-rcon-to-ops": "false"})
    with open(props, "w", encoding="utf-8") as f:
        for k, v in lines.items():
            f.write(f"{k}={v}\n")
    if fresh:
        import shutil
        shutil.rmtree(os.path.join(RUN, "world"), ignore_errors=True)
    gradlew = os.path.join(MOD, "gradlew.bat" if os.name == "nt" else "gradlew")
    p = subprocess.Popen([gradlew, "runServer", "--console=plain", "-Dorg.gradle.jvmargs=-Xmx2G"], cwd=MOD,
                         stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                         encoding="utf-8", errors="replace", bufsize=1)
    q = queue.Queue()

    def pump():
        for line in p.stdout:
            q.put(line)
        q.put(None)

    threading.Thread(target=pump, daemon=True).start()
    start = time.time()

    def wait_for(pattern, limit):
        rx = re.compile(pattern)
        end = time.time() + limit
        while time.time() < end:
            try:
                line = q.get(timeout=1)
            except queue.Empty:
                continue
            if line is None:
                return False
            if "/DEBUG]" not in line:
                sys.stdout.write(line)
                sys.stdout.flush()
            if rx.search(line):
                return True
        return False

    ok = wait_for(r"Done \(\d", timeout)
    print(f"=== server up: {ok} after {time.time() - start:.0f}s", flush=True)
    rcon = None
    if ok:
        wait_for(r"RCON running", 30)
        try:
            rcon = Rcon(RCON_PORT, RCON_PASS)
        except OSError as e:
            print("=== rcon failed:", e, flush=True)
    if rcon:
        for c in cmds:
            cmd, _, pat = c.partition("::")
            print(f"=== > {cmd}", flush=True)
            reply = rcon.command(cmd)
            print(reply.replace("\n\n", "\n"), flush=True)
            if pat and not re.search(pat, reply):
                print(f"=== waited: {wait_for(pat, timeout)}", flush=True)
        try:
            rcon.command("stop")
        except (OSError, ConnectionError):
            pass
    wait_for(r"$^", 90)
    try:
        p.wait(timeout=120)
    except subprocess.TimeoutExpired:
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)], capture_output=True)
    print(f"=== finished after {time.time() - start:.0f}s", flush=True)


if __name__ == "__main__":
    main()
