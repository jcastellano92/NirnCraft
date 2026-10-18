#!/usr/bin/env python3
"""Validate every program of the Skycraft Aether shader pack with glslangValidator, the way Iris would build it:
includes resolved from the pack root, and settings.glsl rewritten per quality profile (shaders.properties
profile.LOW .. profile.ULTRA), so every tier's code path compiles, not only the defaults.

usage (from the worktree root or native/):
    py -3 native/tools/pack_lab_validate.py [--glslang PATH] [--profile LOW|MEDIUM|HIGH|ULTRA|ALL] [--keep DIR]

Exit code 0 when every program of every requested profile compiles.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve()
ROOT = HERE.parents[2]
PACK = ROOT / "shaderpack" / "SkycraftAether" / "shaders"
DEFAULT_GLSLANG = pathlib.Path(r"F:\Projects\Personal\Skycraft\toolchains\VulkanSDK\1.4.357.0\Bin\glslangValidator.exe")
INCLUDE = re.compile(r'^\s*#include\s+"([^"]+)"\s*$')


def profiles() -> dict[str, dict[str, str | bool]]:
    text = (PACK / "shaders.properties").read_text(encoding="utf-8").replace("\\\n", " ")
    out: dict[str, dict[str, str | bool]] = {"DEFAULT": {}}
    for line in text.splitlines():
        m = re.match(r"^\s*profile\.(\w+)\s*=\s*(.*)$", line)
        if not m:
            continue
        opts: dict[str, str | bool] = {}
        for tok in m.group(2).split():
            if tok.startswith("profile."):
                continue
            if tok.startswith("!"):
                opts[tok[1:]] = False
            elif "=" in tok:
                k, v = tok.split("=", 1)
                opts[k] = v
            else:
                opts[tok] = True
        out[m.group(1)] = opts
    return out


def apply_settings(text: str, opts: dict[str, str | bool]) -> str:
    lines = []
    for line in text.splitlines():
        m = re.match(r"^(\s*)(//\s*)?#define\s+(\w+)(\s+(\S+))?(.*)$", line)
        c = re.match(r"^(\s*const\s+\w+\s+(\w+)\s*=\s*)([^;]+)(;.*)$", line)
        if m and m.group(3) in opts:
            name, val = m.group(3), opts[m.group(3)]
            if isinstance(val, bool):
                line = ("" if val else "// ") + "#define " + name + (" " + m.group(5) if m.group(5) else "") + m.group(6)
            else:
                line = m.group(1) + "#define " + name + " " + val + m.group(6)
        elif c and c.group(2) in opts and not isinstance(opts[c.group(2)], bool):
            line = c.group(1) + str(opts[c.group(2)]) + c.group(4)
        lines.append(line)
    return "\n".join(lines) + "\n"


def resolve(path: pathlib.Path, opts, depth=0, seen=None) -> str:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = INCLUDE.match(line)
        if m and depth < 16:
            target = PACK / m.group(1).lstrip("/") if m.group(1).startswith("/") else path.parent / m.group(1)
            body = resolve(target, opts, depth + 1)
            if target.name == "settings.glsl":
                body = apply_settings(body, opts)
            out.append(body)
        else:
            out.append(line)
    return "\n".join(out) + "\n"


def programs() -> list[pathlib.Path]:
    found = sorted(PACK.glob("*.vsh")) + sorted(PACK.glob("*.fsh"))
    for d in sorted(p for p in PACK.iterdir() if p.is_dir() and p.name not in ("lib", "lang")):
        found += sorted(d.glob("*.vsh")) + sorted(d.glob("*.fsh"))
    return found


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--glslang", type=pathlib.Path, default=DEFAULT_GLSLANG)
    ap.add_argument("--profile", default="ALL")
    ap.add_argument("--keep", type=pathlib.Path)
    a = ap.parse_args()
    all_profiles = profiles()
    names = list(all_profiles) if a.profile == "ALL" else [a.profile]
    failures = 0
    total = 0
    with tempfile.TemporaryDirectory() as tmp:
        outdir = a.keep or pathlib.Path(tmp)
        outdir.mkdir(parents=True, exist_ok=True)
        for prof in names:
            opts = all_profiles[prof]
            for prog in programs():
                stage = "vert" if prog.suffix == ".vsh" else "frag"
                rel = prog.relative_to(PACK).as_posix().replace("/", "__")
                src = outdir / f"{prof}__{rel}.{stage}"
                src.write_text(resolve(prog, opts), encoding="utf-8")
                total += 1
                r = subprocess.run([str(a.glslang), "-S", stage, str(src)], capture_output=True, text=True)
                text = (r.stdout + r.stderr).strip()
                bad = r.returncode != 0 or "ERROR" in text
                warn = [l for l in text.splitlines() if "WARNING" in l]
                if bad:
                    failures += 1
                    print(f"FAIL [{prof}] {prog.relative_to(PACK)}\n{text}\n")
                elif warn:
                    print(f"warn [{prof}] {prog.relative_to(PACK)}: " + "; ".join(warn[:3]))
    print(f"pack_lab_validate: {total - failures}/{total} programs compile ({', '.join(names)})")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
