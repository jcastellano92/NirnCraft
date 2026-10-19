"""Build the Linux atlas_core in WSL and ship it into the mod (natives/linux-x86_64), recording the sources it was
built from (natives/manifest.json). Uses the userland toolchain native/build_linux.sh documents (~/.atlas-tc, glibc
2.28 sysroot, no sudo) and its build directory in the Linux home; low priority, half the cores. End users never need
WSL: this only produces the .so on a Windows development machine.

    py -3 tools/dev/build_linux_wsl.py [--distro Ubuntu-24.04] [--test]
"""
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/dev"))
import native_sources  # noqa: E402


def wsl_path(p: Path) -> str:
    d = str(p.resolve())
    return "/mnt/" + d[0].lower() + d[2:].replace("\\", "/")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--distro", default=os.environ.get("NIRNCRAFT_WSL_DISTRO", "Ubuntu-24.04"))
    ap.add_argument("--test", action="store_true", help="run the native suites (software Vulkan: slow)")
    a = ap.parse_args()
    digest = native_sources.source_hash()
    # one build directory per checkout: a shared one silently built whichever worktree configured it first
    build = "~/atlas-build-" + hashlib.sha256(str(ROOT.resolve()).lower().encode()).hexdigest()[:10]
    cores = max(1, (os.cpu_count() or 2) // 2)
    script = (f"cd {wsl_path(ROOT)} && PATH=~/.atlas-tc/env/bin:$PATH CXX=x86_64-conda-linux-gnu-g++ BUILD_DIR={build} "
              f"taskset -c 0-{cores - 1} nice -n 15 native/build_linux.sh {'--software' if a.test else '--no-test'}")
    r = subprocess.run(["wsl", "-d", a.distro, "-e", "bash", "-lc", script])
    if r.returncode:
        print("build_linux_wsl: the WSL build failed (see above); nothing shipped")
        return 1
    built = subprocess.run(["wsl", "-d", a.distro, "-e", "bash", "-lc", f"wslpath -w {build}/libatlas_core.so"],
                           capture_output=True, text=True).stdout.strip()
    if not built or not Path(built).exists():
        print(f"build_linux_wsl: no libatlas_core.so at {built!r}")
        return 1
    dst = ROOT / "mod/src/main/resources/natives/linux-x86_64/libatlas_core.so"
    shutil.copy2(built, dst)
    native_sources.record("linux-x86_64", digest)
    print(f"build_linux_wsl: shipped {dst} (sources {digest[:12]})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
