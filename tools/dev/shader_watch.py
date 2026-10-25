"""Shader hot reload for atlas_core's Vulkan backend: compile the far kernel's entries on every save.

Watches native/shaders/*.slang; on a change compiles farMain, cloudMain, sunMapMain, nearLightMain and
nearUpsampleMain, nearTemporalMain to SPIR-V (the same slangc and flags as native/CMakeLists.txt but -O1 by default: 1.9 s for the far
kernel against 4.7 s at -O3; the driver optimises again, so it draws the same, but time frames on a build) into the
output folder, all at once,
atlas_far.spv last and each by an atomic rename, so a reader never sees half a set. A renderer started with ATLAS_SHADER_DIR=<that folder> (atlas_render, or the game client)
rebuilds its pipelines within 250 ms (vk_backend.cpp hotReload). Bindings must stay the same: a layout change needs
native/build.ps1. The CPU backend is compiled C++ and does not reload.

    py -3 tools/dev/shader_watch.py [--out .work/shaders] [--game] [--once]

--game also writes them to the preview instance's .minecraft/atlas-shaders, which the game's atlas_core reloads from
when it exists (no environment needed), and keeps native/build/Release/atlas_core.dll copied to .minecraft/atlas-dev,
which the game loads instead of the jar's core at its next start (AtlasLibrary: a native change is a restart, not a
jar build). Every harness live run moves .minecraft/atlas-shaders aside first (game.clear_dev_shaders): left there, the
folder made every later run draw with its kernels.
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
ENTRIES = (("cloudMain", "atlas_cloud.spv"), ("sunMapMain", "atlas_sunmap.spv"),
           ("nearLightMain", "atlas_nearlight.spv"), ("nearUpsampleMain", "atlas_nearup.spv"),
           ("nearTemporalMain", "atlas_neartemporal.spv"),
           ("farMain", "atlas_far.spv"))


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
            for entry, name in ENTRIES]  # the entries at once: one entry's time, not six
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


def mirror(src, dests):
    """The compiled set into the other folders the same way (atlas_far.spv last, atomic renames)."""
    for d in dests:
        for _, name in ENTRIES:
            tmp = d / (name + ".tmp")
            tmp.write_bytes((src / name).read_bytes())
            os.replace(tmp, d / name)
    return True


CORE = ROOT / "native/build/Release/atlas_core.dll"


def sync_core(dev):
    """The freshly built core where the game picks it up at its next start (only when it changed)."""
    dst = dev / CORE.name
    try:
        if CORE.exists() and (not dst.exists() or CORE.stat().st_mtime_ns != dst.stat().st_mtime_ns):
            tmp = dev / (CORE.name + ".tmp")
            tmp.write_bytes(CORE.read_bytes())
            os.replace(tmp, dst)
            os.utime(dst, ns=(CORE.stat().st_atime_ns, CORE.stat().st_mtime_ns))
            print(f"shader_watch: core copied to {dst} (the game loads it at its next start)", flush=True)
    except OSError:
        pass  # the linker is still writing it (or the game holds the old copy): the next look takes it


def stamp():
    return max(p.stat().st_mtime_ns for p in SHADERS.glob("*.slang"))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default=str(ROOT / ".work/shaders"))
    ap.add_argument("--once", action="store_true", help="compile once and exit")
    ap.add_argument("--game", action="store_true", help="also into the preview instance's .minecraft/atlas-shaders")
    ap.add_argument("--opt", default="-O1", help="slangc optimisation (-O0 0.9 s, -O1 1.9 s, -O3 4.7 s)")
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    exe = slangc()
    outs = [out]
    if a.game:
        g = Path.home() / "AppData/Roaming/PrismLauncher/instances/NirnCraft-26.3-autotest/.minecraft/atlas-shaders"
        g.mkdir(parents=True, exist_ok=True)
        outs.append(g)
    dev = None
    if a.game:
        dev = Path.home() / "AppData/Roaming/PrismLauncher/instances/NirnCraft-26.3-autotest/.minecraft/atlas-dev"
        dev.mkdir(parents=True, exist_ok=True)
        sync_core(dev)
    last = stamp()
    ok = compile_all(exe, out, a.opt) and mirror(out, outs[1:])
    if a.once:
        sys.exit(0 if ok else 1)
    print(f"shader_watch: watching {SHADERS} (set ATLAS_SHADER_DIR={out})", flush=True)
    while True:
        time.sleep(0.2)
        if dev:
            sync_core(dev)
        now = stamp()
        if now != last:
            last = now
            time.sleep(0.1)  # the editor's write settles
            if compile_all(exe, out, a.opt):
                mirror(out, outs[1:])


if __name__ == "__main__":
    main()
