"""Shader hot reload for atlas_core's Vulkan backend: compile the far kernel's entries on every save.

Watches native/shaders/*.slang; on a change compiles farMain, cloudMain and sunMapMain to SPIR-V (the same slangc and
flags as native/CMakeLists.txt but -O1 by default: 1.9 s for the far kernel against 4.7 s at -O3; the driver
optimises again, so it draws the same, but time frames on a build) into the output folder, all three at once,
atlas_far.spv last and each by an atomic rename, so a reader never sees half a set. A renderer started with ATLAS_SHADER_DIR=<that folder> (atlas_render, or the game client)
rebuilds its pipelines within 250 ms (vk_backend.cpp hotReload). Bindings must stay the same: a layout change needs
native/build.ps1. The CPU backend is compiled C++ and does not reload.

    py -3 tools/dev/shader_watch.py [--out .work/shaders] [--once]
"""
import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHADERS = ROOT / "native/shaders"
KERNEL = SHADERS / "atlas_far.slang"
ENTRIES = (("cloudMain", "atlas_cloud.spv"), ("sunMapMain", "atlas_sunmap.spv"), ("farMain", "atlas_far.spv"))


def slangc():
    cache = ROOT / "native/build/CMakeCache.txt"
    if cache.exists():
        for line in cache.read_text(errors="replace").splitlines():
            if line.startswith("SLANGC:FILEPATH="):
                return line.split("=", 1)[1]
    found = sorted((ROOT.parent / "toolchains").glob("slang-*/bin/slangc*"))
    if found:
        return str(found[-1])
    sys.exit("shader_watch: slangc not found (run native/build.ps1 once)")


def compile_all(exe, out, opt):
    t0 = time.perf_counter()
    runs = [(entry, name, subprocess.Popen([exe, str(KERNEL), "-target", "spirv", "-entry", entry, "-stage", "compute",
                                            opt, "-o", str(out / (name + ".tmp"))],
                                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True))
            for entry, name in ENTRIES]  # the three entries at once: one entry's time, not three
    failed = False
    for entry, name, p in runs:
        text = p.communicate()[0]
        if p.returncode or not (out / (name + ".tmp")).exists():
            errors = [l for l in text.splitlines() if "error" in l.lower()]
            print(f"shader_watch: {entry} failed\n" + "\n".join(errors or text.splitlines()[-20:]))
            failed = True
    if failed:
        return False
    for entry, name in ENTRIES:  # atlas_far.spv last: the renderer reloads on it
        os.replace(out / (name + ".tmp"), out / name)
    print(f"shader_watch: compiled in {time.perf_counter() - t0:.1f} s -> {out}", flush=True)
    return True


def stamp():
    return max(p.stat().st_mtime_ns for p in SHADERS.glob("*.slang"))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default=str(ROOT / ".work/shaders"))
    ap.add_argument("--once", action="store_true", help="compile once and exit")
    ap.add_argument("--opt", default="-O1", help="slangc optimisation (-O0 0.9 s, -O1 1.9 s, -O3 4.7 s)")
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    exe = slangc()
    last = stamp()
    ok = compile_all(exe, out, a.opt)
    if a.once:
        sys.exit(0 if ok else 1)
    print(f"shader_watch: watching {SHADERS} (set ATLAS_SHADER_DIR={out})", flush=True)
    while True:
        time.sleep(0.2)
        now = stamp()
        if now != last:
            last = now
            time.sleep(0.1)  # the editor's write settles
            compile_all(exe, out, a.opt)


if __name__ == "__main__":
    main()
