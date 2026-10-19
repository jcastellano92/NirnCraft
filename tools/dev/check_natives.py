"""Check the atlas_core natives packed in the mod against the ABI the Java binding drives.

For every library under mod/src/main/resources/natives/<os>-<arch>/ this reports the platform (the folder name, checked
against the binary's own header), the size, the ABI version the library reports and how that was read, and compares it
with AtlasNative.ABI_MIN .. ABI_VERSION (mod/src/main/java/com/nirncraft/atlas/AtlasNative.java). AtlasNative.load
refuses a library outside that range, so either side of it is a failure here too.

How the ABI is read, best first:
  load     ctypes loads a temporary copy in a child process and calls atlas_abi_version(): Windows DLLs on Windows,
           Linux .so files on Linux.
  wsl      a Linux .so on Windows: the same child, run by python3 inside WSL (`wsl -e python3`; the default distro, or
           --wsl-distro / NIRNCRAFT_WSL_DISTRO). End users never need WSL; this only lets a Windows box check the .so.
  static   no loader for that platform: the export table (PE) or dynamic symbol table (ELF) gives atlas_abi_version's
           address, and its first instructions are decoded (x86-64 `mov eax, imm32; ret`, arm64 `mov w0, #imm; ret`,
           following one `jmp` thunk). The function is `return ATLAS_ABI_VERSION;` (native/src/api.cpp), so this reads
           the same constant without running the file. Reported as "static"; a body it cannot decode is "unknown".
The static reading is also cross-checked against a loader's when both are available.

usage: py -3 -I tools/dev/check_natives.py [--repo DIR] [--json] [--require-current] [--no-wsl] [--wsl-distro NAME]
exit:  0 every native is within ABI_MIN .. ABI_VERSION; 1 one is older than ABI_MIN (or newer than ABI_VERSION, or
       unreadable); with --require-current also when one is older than ABI_VERSION (the binding's own version).
"""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import sys
import tempfile

LIB_SUFFIXES = (".dll", ".so", ".dylib")
# the child that loads a library: argv[1] = path; prints the version as a decimal number
LOADER = ("import ctypes,sys\n"
          "lib=ctypes.CDLL(sys.argv[1])\n"
          "f=lib.atlas_abi_version\n"
          "f.restype=ctypes.c_uint32\n"
          "f.argtypes=[]\n"
          "print(f())\n")


def abi_str(v):
    return "?" if v is None else f"{v >> 16 & 255}.{v >> 8 & 255}.{v & 255}"


def java_abi(repo):
    src = (repo / "mod/src/main/java/com/nirncraft/atlas/AtlasNative.java").read_text(encoding="utf-8")
    cur = re.search(r"\bABI_VERSION\s*=\s*(0x[0-9A-Fa-f]+|\d+)", src)
    low = re.search(r"\bABI_MIN\s*=\s*(0x[0-9A-Fa-f]+|\d+)", src)
    if not cur or not low:
        raise SystemExit("AtlasNative.java: ABI_VERSION / ABI_MIN not found")
    return int(low.group(1), 0), int(cur.group(1), 0)


# ---- binary headers and the static reading ------------------------------------------------------------------------

def _decode(code, arch):
    """The constant a `return K;` body loads, or None. code: the function's first bytes; arch 'x86_64' / 'aarch64'."""
    if arch == "x86_64":
        if len(code) >= 6 and code[0] == 0xB8 and code[5] == 0xC3:          # mov eax, imm32 ; ret
            return struct.unpack_from("<I", code, 1)[0]
        if len(code) >= 3 and code[0:2] == b"\x31\xC0" and code[2] == 0xC3:  # xor eax, eax ; ret
            return 0
        return None
    if arch == "aarch64" and len(code) >= 8:
        a, b = struct.unpack_from("<II", code, 0)
        if (a & 0xFF80001F) == 0x52800000 and b == 0xD65F03C0:             # movz w0, #imm16, lsl #(hw*16) ; ret
            return ((a >> 5) & 0xFFFF) << (16 * ((a >> 21) & 3))
    return None


def _jmp_target(code, addr, arch):
    if arch == "x86_64" and len(code) >= 5 and code[0] == 0xE9:            # jmp rel32 (an incremental-link thunk)
        return addr + 5 + struct.unpack_from("<i", code, 1)[0]
    return None


def read_pe(data):
    """(arch, {export name: rva}, rva -> file offset) of a PE file, or None."""
    if data[:2] != b"MZ":
        return None
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        return None
    machine, nsec, _, _, _, optsize, _ = struct.unpack_from("<HHIIIHH", data, pe + 4)
    arch = {0x8664: "x86_64", 0xAA64: "aarch64", 0x14C: "x86"}.get(machine, f"machine 0x{machine:x}")
    opt = pe + 24
    magic = struct.unpack_from("<H", data, opt)[0]
    dd = opt + (112 if magic == 0x20B else 96)
    exp_rva, _ = struct.unpack_from("<II", data, dd)
    secs = []
    for i in range(nsec):
        s = opt + optsize + 40 * i
        vsize, va, rawsize, rawptr = struct.unpack_from("<IIII", data, s + 8)
        secs.append((va, max(vsize, rawsize), rawptr))

    def off(rva):
        for va, size, raw in secs:
            if va <= rva < va + size:
                return rva - va + raw
        return None

    exports = {}
    e = off(exp_rva) if exp_rva else None
    if e is not None:
        nfun, nname, afun, aname, aord = struct.unpack_from("<IIIII", data, e + 20)
        for i in range(nname):
            name_off = off(struct.unpack_from("<I", data, off(aname) + 4 * i)[0])
            name = data[name_off:data.index(b"\0", name_off)].decode("ascii", "replace")
            ordinal = struct.unpack_from("<H", data, off(aord) + 2 * i)[0]
            exports[name] = struct.unpack_from("<I", data, off(afun) + 4 * ordinal)[0]
    return arch, exports, off


def read_elf(data):
    """(arch, {dynamic symbol: vaddr}, vaddr -> file offset) of a 64-bit little-endian ELF file, or None."""
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return None
    machine = struct.unpack_from("<H", data, 18)[0]
    arch = {62: "x86_64", 183: "aarch64"}.get(machine, f"machine {machine}")
    phoff, shoff = struct.unpack_from("<QQ", data, 32)
    phentsize, phnum, shentsize, shnum = struct.unpack_from("<HHHH", data, 54)
    loads = []
    for i in range(phnum):
        p = phoff + i * phentsize
        ptype, _, poff, vaddr, _, filesz = struct.unpack_from("<IIQQQQ", data, p)
        if ptype == 1:
            loads.append((vaddr, filesz, poff))

    def off(addr):
        for va, size, fo in loads:
            if va <= addr < va + size:
                return addr - va + fo
        return None

    secs = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shentsize) for i in range(shnum)]
    syms = {}
    for s in secs:
        if s[1] != 11:                                                     # SHT_DYNSYM
            continue
        strtab = secs[s[6]]
        for j in range(s[5] // 24):
            name, info, _, shndx, value, _ = struct.unpack_from("<IBBHQQ", data, s[4] + 24 * j)
            if shndx and value and (info & 0xF) == 2:                      # defined STT_FUNC
                n0 = strtab[4] + name
                syms[data[n0:data.index(b"\0", n0)].decode("ascii", "replace")] = value
    return arch, syms, off


def inspect(path):
    """Header facts and the static ABI reading of one library."""
    data = path.read_bytes()
    parsed, fmt = read_pe(data), "PE"
    if parsed is None:
        parsed, fmt = read_elf(data), "ELF"
    if parsed is None:
        return {"format": "unknown", "arch": None, "exports": [], "static": None}
    arch, exports, off = parsed
    static = None
    addr = exports.get("atlas_abi_version")
    for _ in range(2):                                                     # the body, or one jmp thunk then the body
        o = off(addr) if addr is not None else None
        if o is None:
            break
        code = data[o:o + 16]
        static = _decode(code, arch)
        if static is not None:
            break
        addr = _jmp_target(code, addr, arch)
    return {"format": fmt, "arch": arch, "exports": sorted(n for n in exports if n.startswith("atlas_")), "static": static}


# ---- loaders --------------------------------------------------------------------------------------------------------

def _run_child(cmd, timeout=60):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as e:
        return None, str(e)
    if r.returncode != 0:
        return None, (r.stderr.strip().splitlines() or [f"exit {r.returncode}"])[-1]
    try:
        return int(r.stdout.strip().splitlines()[-1]), None
    except (ValueError, IndexError):
        return None, "no version printed"


def host_platform():
    m = platform.machine().lower()
    arch = {"amd64": "x86_64", "x86_64": "x86_64", "arm64": "aarch64", "aarch64": "aarch64"}.get(m, m)
    osn = {"win32": "windows", "linux": "linux", "darwin": "macos"}.get(sys.platform, sys.platform)
    return osn, arch


def load_here(path):
    """atlas_abi_version() of a library this host can load, through a copy (the packed file is never locked)."""
    with tempfile.TemporaryDirectory(prefix="check_natives_") as tmp:
        copy = Path(tmp) / path.name
        shutil.copy2(path, copy)
        return _run_child([sys.executable, "-I", "-c", LOADER, str(copy)])


def wsl_available(distro):
    if sys.platform != "win32" or shutil.which("wsl") is None:
        return False
    base = ["wsl"] + (["-d", distro] if distro else [])
    try:
        r = subprocess.run(base + ["-e", "python3", "-c", "print(1)"], capture_output=True, text=True, timeout=60)
        return r.returncode == 0 and r.stdout.strip() == "1"
    except (OSError, subprocess.TimeoutExpired):
        return False


def load_wsl(path, distro):
    """atlas_abi_version() of a Linux library on Windows, by python3 inside WSL, through a copy in the Linux /tmp."""
    base = ["wsl"] + (["-d", distro] if distro else [])
    try:
        win = subprocess.run(base + ["-e", "wslpath", "-a", str(path).replace("\\", "/")], capture_output=True,
                             text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as e:
        return None, str(e)
    if win.returncode != 0:
        return None, "wslpath failed"
    src = win.stdout.strip()
    # copy inside WSL (mktemp dir), load, clean up: one child, the library never runs from the Windows mount
    script = ("import ctypes,os,shutil,sys,tempfile\n"
              "d=tempfile.mkdtemp(prefix='check_natives_')\n"
              "p=os.path.join(d,os.path.basename(sys.argv[1]))\n"
              "shutil.copy2(sys.argv[1],p)\n"
              "try:\n"
              "    lib=ctypes.CDLL(p); f=lib.atlas_abi_version; f.restype=ctypes.c_uint32; f.argtypes=[]; print(f())\n"
              "finally:\n"
              "    shutil.rmtree(d,ignore_errors=True)\n")
    return _run_child(base + ["-e", "python3", "-I", "-c", script, src])


# ---- main -----------------------------------------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    ap.add_argument("--json", action="store_true", help="print one JSON document instead of the table")
    ap.add_argument("--require-current", action="store_true",
                    help="also fail when a native is older than AtlasNative.ABI_VERSION")
    ap.add_argument("--no-wsl", action="store_true", help="never use WSL to load Linux natives on Windows")
    ap.add_argument("--wsl-distro", default=os.environ.get("NIRNCRAFT_WSL_DISTRO") or None)
    args = ap.parse_args()

    repo = args.repo.resolve()
    abi_min, abi_cur = java_abi(repo)
    root = repo / "mod/src/main/resources/natives"
    libs = sorted(p for p in root.glob("*/*") if p.is_file() and p.suffix in LIB_SUFFIXES)
    host_os, host_arch = host_platform()
    wsl = None  # probed once, on first need

    rows, failed = [], False
    for p in libs:
        folder = p.parent.name
        dir_os, _, dir_arch = folder.partition("-")
        info = inspect(p)
        version, method, note = None, None, None
        native_here = dir_os == host_os and info["arch"] == host_arch
        if native_here:
            version, err = load_here(p)
            method, note = ("load", None) if version is not None else (None, f"load failed: {err}")
        elif dir_os == "linux" and host_os == "windows" and not args.no_wsl and info["arch"] == "x86_64":
            if wsl is None:
                wsl = wsl_available(args.wsl_distro)
            if wsl:
                version, err = load_wsl(p, args.wsl_distro)
                method, note = ("wsl", None) if version is not None else (None, f"wsl load failed: {err}")
        if version is None and info["static"] is not None:
            version, method = info["static"], "static"
        elif version is not None and info["static"] is not None and info["static"] != version:
            note = f"static reading {abi_str(info['static'])} disagrees"

        problems = []
        if info["arch"] and dir_arch and info["arch"] != dir_arch:
            problems.append(f"binary is {info['arch']}, folder says {dir_arch}")
        if version is None:
            problems.append("ABI unreadable")
        elif version < abi_min:
            problems.append(f"older than ABI_MIN {abi_str(abi_min)}")
        elif version > abi_cur:
            problems.append(f"newer than ABI_VERSION {abi_str(abi_cur)} (AtlasNative refuses it)")
        elif version < abi_cur:
            msg = f"older than ABI_VERSION {abi_str(abi_cur)}"
            if args.require_current:
                problems.append(msg)
            else:
                note = (note + "; " if note else "") + msg + " (loads; newer calls absent)"
        failed |= bool(problems)
        rows.append({
            "file": p.relative_to(repo).as_posix(), "os": dir_os, "arch": info["arch"] or dir_arch,
            "format": info["format"], "size": p.stat().st_size, "abi": abi_str(version), "abi_raw": version,
            "method": method, "exports": len(info["exports"]), "ok": not problems,
            "problems": problems, "note": note,
        })

    if not libs:
        failed = True
    if args.json:
        print(json.dumps({"abi_min": abi_str(abi_min), "abi_version": abi_str(abi_cur), "natives": rows,
                          "ok": not failed}, indent=2))
    else:
        print(f"AtlasNative: ABI_MIN {abi_str(abi_min)}, ABI_VERSION {abi_str(abi_cur)}")
        if not libs:
            print(f"no natives under {root}")
        for r in rows:
            status = "ok" if r["ok"] else "FAIL: " + "; ".join(r["problems"])
            print(f"  {r['os']:<8} {r['arch']:<8} {r['size']:>9} B  abi {r['abi']:<7} ({r['method'] or '-'}, "
                  f"{r['exports']} atlas_* exports)  {status}  {r['file']}")
            if r["note"]:
                print(f"           note: {r['note']}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
