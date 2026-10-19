"""One-shot, re-runnable rename: Skycraft -> NirnCraft (mod id / namespace nirncraft, Java com.nirncraft) and the
far-field renderer aether -> Infinite ATLAS (atlas_core, atlas_* C API, com.nirncraft.atlas, Atlas* classes).
"NirnCraft is powered by Infinite ATLAS" (Adaptive Terrain Language and Abstraction Specification).

    py -3 tools/dev/rename_nirncraft.py [--root .] [--apply]      (dry run without --apply: counts only)

Rules:
* skycraft / Skycraft / SKYCRAFT -> nirncraft / NirnCraft / NIRNCRAFT in every text file, except machine paths that
  are not ours to move (F:\\Projects\\Personal\\Skycraft, E:\\Skycraft-scratch) and the Prism backup root.
* aether (the renderer) -> atlas only where it is an identifier: in code-like files (Java, C/C++, Slang, GLSL, CMake,
  scripts, the shader pack, docs/AETHER*) and only when not followed by a lower-case letter, so the lore words
  Aetherius / Aetherian / aetherial / aetheric survive. In data and lore (mod resources) only the renderer's own ids
  (nirncraft:aether/..., textures/.../aether*) change. Plain-English "aether" in code comments becomes "atlas"; review
  the diff for prose.
* Paths: com/skycraft -> com/nirncraft, .../aether -> .../atlas, assets|data|resources/skycraft -> .../nirncraft,
  Aether*.java -> Atlas*.java, aether_* / AETHER* files -> atlas_* / ATLAS*, shaderpack/SkycraftAether ->
  shaderpack/NirnCraft. Renames use `git mv` when the tree is a git checkout.
"""
import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ALWAYS_SKIP = {".git", ".claude", "node_modules", "__pycache__", ".gradle", ".work", ".reference"}
SKIP_DIRS = {".git", ".claude", "build", "dist", "logs", "node_modules", "__pycache__", ".gradle", ".work", "out",
             "build-linux", "run", ".reference", "gen"}
BINARY = {".png", ".jpg", ".jpeg", ".gif", ".bin", ".jar", ".dll", ".so", ".exe", ".zip", ".nbt", ".ogg", ".wav",
          ".mp3", ".ttf", ".otf", ".spv", ".class", ".pdf", ".ico", ".gz", ".mca", ".dat", ".lib", ".exp", ".pdb",
          ".obj", ".tga", ".dds", ".webp", ".psd", ".xcf", ".blend"}
CODE = {".java", ".c", ".cc", ".cpp", ".h", ".hpp", ".slang", ".glsl", ".vsh", ".fsh", ".gsh", ".csh", ".txt",
        ".cmake", ".ps1", ".py", ".sh", ".bat", ".properties", ".lang", ".toml", ".gradle", ".kts", ".md", ".json",
        ".map", ".mcmeta", ".cfg", ".ini", ".yml", ".yaml", ".mjs", ".js", ".ts"}
# not ours to move: paths on this machine and the Prism backup root (kept by name)
PROTECT = [r"Personal[\\/]+Skycraft", r"Skycraft-scratch", r"skycraft-263-backups"]


def is_lore_path(rel):
    r = rel.replace("\\", "/")
    return r.startswith("mod/src/main/resources/") and "/shaders/" not in r


def rename_text(text, rel):
    holds = {}

    def hold(m):
        key = f"\x00{len(holds)}\x00"
        holds[key] = m.group(0)
        return key

    for p in PROTECT:
        text = re.sub(p, hold, text)
    text = text.replace("Skycraft", "NirnCraft").replace("SKYCRAFT", "NIRNCRAFT").replace("skycraft", "nirncraft")
    # NirnCraft's own lowercase / display forms that came out of compound names
    text = text.replace("NirnCraftAether", "NirnCraft")  # the shader pack's old name (SkycraftAether)
    if is_lore_path(rel):
        # data / lore: only the renderer's own ids
        text = re.sub(r"(nirncraft:)aether/", r"\1atlas/", text)
        text = re.sub(r"(textures/[\w/]*?)aether", r"\1atlas", text)
    else:
        def case(m):
            w = m.group(0)
            return "ATLAS" if w.isupper() else ("Atlas" if w[0].isupper() else "atlas")
        text = re.sub(r"(?<![A-Za-z])(?:aether|Aether|AETHER)(?![a-z])", case, text)
        text = re.sub(r"(?<=[a-z])Aether(?![a-z])", "Atlas", text)  # camelCase: aetherColor done above; farAether
    for k, v in holds.items():
        text = text.replace(k, v)
    return text


def new_rel(rel):
    parts = rel.replace("\\", "/").split("/")
    out = []
    for i, p in enumerate(parts):
        q = p
        if p == "SkycraftAether":
            q = "NirnCraft"
        q = q.replace("Skycraft", "NirnCraft").replace("skycraft", "nirncraft").replace("SKYCRAFT", "NIRNCRAFT")
        if not is_lore_path(rel):  # lore / data paths never rename "aether" (Aetherius etc.)
            q = re.sub(r"(?<![A-Za-z])aether(?![a-z])", "atlas", q)
            q = re.sub(r"(?<![A-Za-z])Aether(?![a-z])", "Atlas", q)
            q = re.sub(r"(?<![A-Za-z])AETHER(?![a-z])", "ATLAS", q)
        out.append(q)
    return "/".join(out)


def walk(root):
    for dp, dn, fn in os.walk(root):
        in_src = "src" in Path(dp).parts  # source packages may be named build / out / run (explore/build)
        dn[:] = [d for d in dn if not (Path(dp) / d).is_symlink()
                 and d not in ALWAYS_SKIP and (in_src or d not in SKIP_DIRS)]
        for f in fn:
            if f == ".git":
                continue  # a worktree's .git is a file
            yield Path(dp) / f


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--apply", action="store_true")
    a = ap.parse_args()
    root = Path(a.root).resolve()
    git = (root / ".git").exists()
    edited = renamed = occurrences = 0
    files = list(walk(root))
    for f in files:  # 1. contents
        rel = str(f.relative_to(root))
        if f.suffix.lower() in BINARY or (f.suffix.lower() not in CODE and f.suffix):
            continue
        try:
            raw = f.read_bytes()
            text = raw.decode("utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        new = rename_text(text, rel)
        if new != text:
            edited += 1
            occurrences += len(re.findall(r"(?i)skycraft", text))
            if a.apply:
                f.write_bytes(new.encode("utf-8"))
    moves = []  # 2. paths, deepest first
    for f in files:
        rel = str(f.relative_to(root)).replace("\\", "/")
        nr = new_rel(rel)
        if nr != rel:
            moves.append((rel, nr))
    for rel, nr in sorted(moves, key=lambda m: -m[0].count("/")):
        renamed += 1
        if not a.apply:
            continue
        dst = root / nr
        dst.parent.mkdir(parents=True, exist_ok=True)
        if git:
            r = subprocess.run(["git", "mv", "-k", rel, nr], cwd=root, capture_output=True, text=True)
            if (root / rel).exists() and not dst.exists():
                os.replace(root / rel, dst)
        else:
            os.replace(root / rel, dst)
    if a.apply:  # empty old directories
        for dp, dn, fn in sorted(os.walk(root), key=lambda t: -t[0].count(os.sep)):
            rp = Path(dp).relative_to(root).parts
            if any(s in rp for s in ALWAYS_SKIP) or ("src" not in rp and any(s in rp for s in SKIP_DIRS)):
                continue
            if not dn and not fn and re.search(r"(?i)skycraft|(?<![a-z])aether(?![a-z])", Path(dp).name):
                os.rmdir(dp)
    print(f"{'applied' if a.apply else 'dry run'}: {edited} files edited ({occurrences} skycraft occurrences), "
          f"{renamed} paths renamed")


if __name__ == "__main__":
    main()
