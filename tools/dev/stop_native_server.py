"""Orderly stop for an explicitly selected, isolated Java 25 NeoForge dev server.

Fallback when console stdin and the ticking command inbox are unavailable. This
does not kill processes, rewrite classes or access private game members.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--control", type=Path, help="Exact nirncraft.dev directory; defaults to runtime sibling server-control")
    parser.add_argument("--scratch", type=Path, required=True)
    args = parser.parse_args()
    java, runtime, scratch = args.java.resolve(strict=True), args.runtime.resolve(strict=True), args.scratch.resolve()
    control = (args.control or runtime.parent / 'server-control').resolve(strict=True)
    for name in ("server.properties", "eula.txt", "logs/latest.log"):
        if not (runtime / name).is_file():
            parser.error(f"Not a prepared dedicated-server runtime: missing {name}")
    if args.pid <= 0:
        parser.error("--pid must be a positive exact process ID")
    scratch.mkdir(parents=True, exist_ok=True)
    classes = scratch / "classes"
    classes.mkdir(exist_ok=True)
    source = Path(__file__).with_name("NirnCraftServerStop.java")
    subprocess.run([str(java.with_name("javac.exe")), "--release", "25", "--add-modules", "jdk.attach",
                    "-d", str(classes), str(source)], check=True, timeout=30)
    manifest = scratch / "MANIFEST.MF"
    manifest.write_text("Manifest-Version: 1.0\nAgent-Class: NirnCraftServerStop\n"
                        "Can-Redefine-Classes: false\nCan-Retransform-Classes: false\n\n", encoding="ascii")
    agent = scratch / "nirncraft-orderly-stop.jar"
    subprocess.run([str(java.with_name("jar.exe")), "--create", "--file", str(agent), "--manifest", str(manifest),
                    "-C", str(classes), "."], check=True, timeout=30)
    receipt = scratch / f"stop-{args.pid}.txt"
    if receipt.exists():
        parser.error(f"Existing stop receipt must be retained: choose a fresh --scratch directory ({receipt})")
    subprocess.run([str(java), "--add-modules", "jdk.attach", "-cp", str(agent), "NirnCraftServerStop",
                    str(args.pid), str(java), str(runtime), str(control), str(agent), str(receipt)], check=True, timeout=30)
    print(f"Orderly stop dispatched. Verify world-save logs and process exit. Receipt: {receipt}")


if __name__ == "__main__":
    main()
