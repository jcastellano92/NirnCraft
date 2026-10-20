"""Compile every NirnCraft GLSL shader offline, before the game does (seconds, not a launch).

The game compiles our core shaders only when a pipeline first uses them, and a compile error there turns the near pass
off or crashes the client (a stale function after a merge did both). This resolves #include <namespace:path> the way the
game does (nirncraft: mod resources; minecraft: the client jar's shaders/include, extracted once to .work/mcshaders),
then compiles each shader with glslangValidator from the Vulkan SDK in the variants the game builds (plain, and with the
macros it injects: ALPHA_CUTOUT, MULTIDRAW_TERRAIN), and exits non-zero on any error.

    py -3 tools/dev/glsl_check.py            # all shaders
    py -3 tools/dev/glsl_check.py near_forward  # names containing this
    py -3 tools/dev/glsl_check.py --selftest # proves a broken shader fails and a good one passes
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHADERS = ROOT / "mod/src/main/resources/assets/nirncraft/shaders"
WORK = ROOT / ".work/mcshaders"
GLSLANG = ROOT.parent / "toolchains/VulkanSDK/1.4.357.0/Bin/glslangValidator.exe"
CLIENT_JAR = Path.home() / "AppData/Roaming/PrismLauncher/libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar"
INCLUDE = re.compile(r'^\s*#include\s*<([a-z0-9_]+):([^>]+)>', re.M)
VARIANTS = [{}, {"ALPHA_CUTOUT": "0.5"}, {"MULTIDRAW_TERRAIN": "1"}]


def minecraft_includes() -> Path:
    inc = WORK / "include"
    if not inc.exists():
        with zipfile.ZipFile(CLIENT_JAR) as z:
            for n in z.namelist():
                if n.startswith("assets/minecraft/shaders/include/") and not n.endswith("/"):  # (not the folders)
                    out = inc / n[len("assets/minecraft/shaders/include/"):]
                    out.parent.mkdir(parents=True, exist_ok=True)
                    out.write_bytes(z.read(n))
    return inc


def resolve(ns: str, path: str) -> Path:
    if ns == "nirncraft":
        return SHADERS / "include" / path
    if ns == "minecraft":
        return minecraft_includes() / path
    raise FileNotFoundError(f"{ns}:{path}")


def expand(text: str, seen: set[str]) -> str:
    def sub(m):
        key = f"{m.group(1)}:{m.group(2)}"
        if key in seen:
            return ""  # (include guards in the files cover the rest)
        seen.add(key)
        return expand(resolve(m.group(1), m.group(2)).read_text(encoding="utf-8"), seen)
    return INCLUDE.sub(sub, text)


def compile_one(src: Path, text: str | None = None) -> list[str]:
    """Error lines for shader src (empty: compiled in every variant)."""
    stage = "frag" if src.suffix == ".fsh" else "vert"
    body = expand(text if text is not None else src.read_text(encoding="utf-8"), set())
    lines = body.splitlines()
    version = next((i for i, l in enumerate(lines) if l.strip().startswith("#version")), None)
    errors = []
    for defs in VARIANTS:
        head = lines[: version + 1] if version is not None else ["#version 330"]
        rest = lines[version + 1:] if version is not None else lines
        out = "\n".join(head + [f"#define {k} {v}" for k, v in defs.items()] + rest) + "\n"
        with tempfile.NamedTemporaryFile("w", suffix="." + stage, delete=False, encoding="utf-8") as f:
            f.write(out)
            tmp = f.name
        r = subprocess.run([str(GLSLANG), tmp], capture_output=True, text=True)
        Path(tmp).unlink(missing_ok=True)
        if r.returncode != 0:
            msg = [l for l in (r.stdout + r.stderr).splitlines() if "ERROR" in l]
            errors.append(f"{src.name} {defs or '(plain)'}: " + "; ".join(msg[:4]))
    return errors


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("name", nargs="?", default="")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if not GLSLANG.exists():
        print(f"glsl_check: no glslangValidator at {GLSLANG}")
        return 2
    if a.selftest:
        good = SHADERS / "core/near_gbuffer.fsh"
        bad = compile_one(good, good.read_text(encoding="utf-8").replace("void main() {", "void main() { undefinedCall();", 1))
        ok = not compile_one(good) and bool(bad)
        print("glsl_check selftest:", "ok" if ok else "FAILED", "(a good shader passes, a broken one fails)")
        return 0 if ok else 1
    files = sorted(p for p in (SHADERS / "core").glob("*.[fv]sh") if a.name in p.name)
    errors = []
    for f in files:
        errors += compile_one(f)
    for e in errors:
        print("ERROR", e)
    print(f"glsl_check: {len(files)} shaders, {len(errors)} failing variants")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
