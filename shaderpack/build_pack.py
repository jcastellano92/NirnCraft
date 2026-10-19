"""Validate and package the NirnCraft Atlas shader pack.

Resolves #include the way Iris does ("/lib/x.glsl" is relative to shaders/), compiles every program stage with the
Vulkan SDK's glslangValidator (desktop GLSL, compatibility profile), then zips the pack for Iris. Iris itself still
patches and links the programs at load time; this catches syntax/type errors before the pack reaches the game.

usage: py -3 -I shaderpack/build_pack.py [--no-zip]
"""
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PACK = HERE / "InfiniteATLAS"
SHADERS = PACK / "shaders"
OUT = HERE / "build"
GLSLANG = (Path(os.environ["VULKAN_SDK"]) if "VULKAN_SDK" in os.environ else next((d / "toolchains" for d in Path(__file__).resolve().parents if (d / "toolchains").is_dir()), Path("toolchains")) / "VulkanSDK/1.4.357.0") / "Bin" / "glslangValidator.exe"
INCLUDE = re.compile(r'^\s*#include\s+"([^"]+)"\s*$')


def resolve(path, seen=None, depth=0):
    if depth > 16:
        raise SystemExit(f"include depth exceeded at {path}")
    lines = []
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        m = INCLUDE.match(line)
        if m:
            inc = m.group(1)
            target = SHADERS / inc.lstrip("/") if inc.startswith("/") else path.parent / inc
            if not target.exists():
                raise SystemExit(f"{path.relative_to(PACK)}:{n}: missing include {inc}")
            lines.append(f"// begin {inc}")
            lines.extend(resolve(target, seen, depth + 1))
            lines.append(f"// end {inc}")
        else:
            lines.append(line)
    return lines


def main():
    if not GLSLANG.exists():
        raise SystemExit(f"glslangValidator not found at {GLSLANG}")
    gen = OUT / "resolved"
    if gen.exists():
        shutil.rmtree(gen)
    gen.mkdir(parents=True)
    failures = 0
    programs = sorted(p for p in SHADERS.iterdir() if p.suffix in (".vsh", ".fsh"))
    for p in programs:
        stage = "vert" if p.suffix == ".vsh" else "frag"
        src = "\n".join(resolve(p)) + "\n"
        dst = gen / f"{p.stem}.{stage}"
        dst.write_text(src, encoding="utf-8")
        r = subprocess.run([str(GLSLANG), "-S", stage, str(dst)], capture_output=True, text=True)
        if r.returncode != 0:
            failures += 1
            print(f"FAIL {p.name}")
            print("  " + r.stdout.strip().replace("\n", "\n  "))
    print(f"{len(programs) - failures}/{len(programs)} program stages compile")
    if failures:
        sys.exit(1)
    if "--no-zip" not in sys.argv:
        z = OUT / "InfiniteATLAS.zip"
        with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED) as zf:
            for f in sorted(PACK.rglob("*")):
                if f.is_file():
                    zf.write(f, f.relative_to(PACK).as_posix())
        print(f"wrote {z} ({z.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
