"""Entity constructors must not use what 26.3 only assigns after construction.

26.3 gives an entity its id after the constructor (the server when it joins a level, the client from the spawn
packet). getId() inside a constructor throws "Tried to access entity ID before ID assignment"; on the client that
happens while handling the spawn packet, so every player the entity is sent to disconnects (Network Protocol Error).
Found 2026-10-19 in DragonEntity and ShipEntity (multipart part ids). Hand part ids out in setId() instead.

Checks every constructor that takes a Level (entities, their parts). Exit 1 with ERR lines when one is found.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "mod/src/main/java"
FORBIDDEN = ("getId()",)


def constructors(text, cls):
    for m in re.finditer(r"\b" + re.escape(cls) + r"\s*\(([^)]*)\)\s*(?:throws[^{]*)?\{", text):
        if "Level" not in m.group(1) or re.search(r"\bnew\s+$", text[:m.start()][-6:]):
            continue
        i, depth = m.end(), 1
        j = i
        while j < len(text) and depth:
            depth += {"{": 1, "}": -1}.get(text[j], 0)
            j += 1
        yield i, text[i:j]


def main():
    bad = 0
    for f in sorted(SRC.rglob("*.java")):
        text = f.read_text(encoding="utf-8", errors="replace")
        for start, body in constructors(text, f.stem):
            for word in FORBIDDEN:
                for k in re.finditer(re.escape(word), body):
                    line = text[:start + k.start()].count("\n") + 1
                    code = body[:k.start()].rsplit("\n", 1)[-1].strip() + word
                    if code.lstrip().startswith("//"):
                        continue
                    print(f"ERR {f.relative_to(ROOT).as_posix()}:{line}: {word} in a constructor ({code[:90]}); "
                          f"26.3 assigns entity ids after construction: use setId()")
                    bad += 1
    print(f"entity construction: {bad} problem(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
