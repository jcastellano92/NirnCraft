"""Java hot swap into the running preview client (NirnCraft-26.3-autotest): method bodies change in seconds, no
rebuild or restart.

Compiles the given sources (default: every mod/src/main/java file changed against HEAD and in the work tree) with
javac against the build's compiled classes and the Gradle compile classpath (mod/.classpath, tools/dev/ide_classpath.py),
then redefines each resulting class that the client has loaded through its JDWP agent (127.0.0.1:5006, added to the
instance's JvmArgs by --install). A structural change (a field, method or signature added or removed) is refused by
the JVM: rebuild with tools/dev/build_263.py and restart. Classes the client has not loaded yet are skipped; they
load from the jar, so a file whose class was not loaded needs the rebuild too (reported).

    py -3 tools/dev/client_hotswap.py [FILE.java ...] [--port 5006]
    py -3 tools/dev/client_hotswap.py --install      # add the JDWP agent to the autotest instance (client closed)
"""
import argparse
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from hotswap import JdwpClient  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "mod/src/main/java"
BUILT = Path("E:/NirnCraft-scratch/migration-26.3/build/classes/java/main")
INSTANCE = Path.home() / "AppData/Roaming/PrismLauncher/instances/NirnCraft-26.3-autotest/instance.cfg"
AGENT = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5006"


def javac():
    for p in sorted(Path("E:/NirnCraft-scratch/toolchains").glob("*jdk*25*/bin/javac.exe")) + \
             sorted((Path.home() / ".vscode/extensions").glob("redhat.java-*/jre/25*/bin/javac.exe")):
        return str(p)
    sys.exit("client_hotswap: no Java 25 javac found")


def classpath():
    cp = ROOT / "mod/.classpath"
    if not cp.exists():
        sys.exit("client_hotswap: mod/.classpath missing: py -3 tools/dev/ide_classpath.py")
    libs = []
    for path in re.findall(r'kind="lib" path="([^"]+)"', cp.read_text(encoding="utf-8")):
        alt = path.replace("Skycraft-scratch", "NirnCraft-scratch")  # the build's own scratch when it has the jar
        libs.append(alt if Path(alt).exists() else path)
    # the core the client runs first (the build folder can lag it: classes compiled against it missed newer methods)
    core = INSTANCE.parent / ".minecraft/mods/nirncraft-core.jar"
    return ([str(core)] if core.exists() else []) + [str(BUILT)] + libs


def changed():
    out = subprocess.run(["git", "-C", str(ROOT), "diff", "--name-only", "HEAD", "--", "mod/src/main/java"],
                         capture_output=True, text=True).stdout.split()
    return [ROOT / p for p in out if p.endswith(".java") and (ROOT / p).exists()]


def install():
    text = INSTANCE.read_text(encoding="utf-8")
    if AGENT in text:
        print("client_hotswap: the agent is already in the instance's JvmArgs")
        return 0
    text = re.sub(r'^JvmArgs="', 'JvmArgs="' + AGENT + " ", text, count=1, flags=re.M)
    INSTANCE.write_text(text, encoding="utf-8")
    print("client_hotswap: JDWP agent added (127.0.0.1:5006); it takes effect at the next client start")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("files", nargs="*", type=Path)
    ap.add_argument("--port", type=int, default=5006)
    ap.add_argument("--install", action="store_true")
    a = ap.parse_args()
    if a.install:
        return install()
    files = [f.resolve() for f in a.files] or changed()
    if not files:
        print("client_hotswap: nothing changed")
        return 0
    t0 = time.perf_counter()
    with tempfile.TemporaryDirectory() as td:
        out = Path(td)
        args = Path(td) / "args.txt"  # the classpath is long: an @file keeps Windows' command line short
        args.write_text("-cp\n\"" + ";".join(classpath()).replace("\\", "/") + "\"\n", encoding="utf-8")
        r = subprocess.run([javac(), "--release", "25", "-nowarn", "-proc:none", "-encoding", "UTF-8", "-d", str(out),
                            f"@{args}"] + [str(f) for f in files], capture_output=True, text=True)
        if r.returncode:
            print("client_hotswap: javac failed\n" + (r.stdout + r.stderr)[-3000:])
            return 1
        compiled = time.perf_counter() - t0
        try:
            jdwp = JdwpClient("127.0.0.1", a.port)
        except OSError as e:
            print(f"client_hotswap: no JDWP agent on 127.0.0.1:{a.port} ({e}); --install, then restart the client")
            return 2
        try:
            jdwp.fetch_id_sizes()
            defs, unloaded = [], []
            for cls in sorted(out.rglob("*.class")):
                name = cls.relative_to(out).as_posix()[:-6]
                ids = jdwp.classes_by_signature(f"L{name};")
                if ids:
                    defs += [(i, cls.read_bytes()) for i in ids]
                elif "$" not in name:
                    unloaded.append(name)
            err = jdwp.redefine_classes(defs) if defs else 0
        finally:
            jdwp.close()
    if err:
        hint = {62: "a schema change (field / method added or removed)", 63: "a method was added",
                64: "a method was removed or changed signature", 66: "class modifiers changed",
                67: "a method's modifiers changed"}.get(err, f"JDWP error {err}")
        print(f"client_hotswap: refused: {hint}; rebuild (tools/dev/build_263.py --offline jar) and restart")
        return 3
    print(f"client_hotswap: {len(defs)} classes swapped from {len(files)} files in {time.perf_counter() - t0:.1f} s "
          f"(javac {compiled:.1f} s)" + (f"; not loaded yet (need a rebuild to change): {', '.join(unloaded)}" if unloaded else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
