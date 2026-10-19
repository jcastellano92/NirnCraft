"""Test gaps and commit-time recommendations.

coverage()   every subject the game defines (subject index) against the views its kind should have: never captured,
             failing, flagged.
screens()    every Screen class in the mod against the scenes that open it (or docs/UI-COVERAGE.md).
diff_ids()   ids a change adds in registration-like Java (register("x"), CreatureSpec("x"), Spell ids ...) that the
             subject index does not know yet: the index needs refreshing and the new subject needs its views.
recommend()  for a diff: the tests it affects (impact.json), the new ids and screens it brings, the gaps it leaves.
"""
import re
import subprocess
from pathlib import Path

import catalog
import results

ROOT = catalog.ROOT
JAVA = ROOT / "mod/src/main/java"
ID_IN_CODE = re.compile(r'(?:register\w*|Spec|Spell|Kind|Def|of)\(\s*"([a-z][a-z0-9_]{2,})"')


def _captured(sid):
    for v in ("shaders", "vanilla", "lab", "measure"):
        rec = results.latest(sid, v)
        if rec:
            return rec
    return None


def coverage(kind=None):
    """{subject: {"kind", "views": n, "never": [...], "failing": [...], "flagged": [...]}} for the subject scenes."""
    out = {}
    scenes = catalog.load()
    for s in scenes:
        subj = next((t[8:] for t in s.get("tags", []) if t.startswith("subject:")), None) or \
            next((t[15:] for t in s.get("tags", []) if t.startswith("gallery:entity:")), None)
        if not subj:
            continue
        k = next((t[5:] for t in s.get("tags", []) if t.startswith("kind:")), None) or "entity"
        if kind and k != kind:
            continue
        e = out.setdefault(f"{k}:{subj}", {"kind": k, "views": 0, "never": [], "failing": [], "flagged": []})
        e["views"] += 1
        rec = _captured(s["id"])
        if not rec:
            e["never"].append(s["id"])
        elif rec.get("status") in ("fail", "error", "crash"):
            e["failing"].append(s["id"])
        elif rec.get("status") == "flag":
            e["flagged"].append(s["id"])
    return out


def screens():
    """[(Screen class, covered?)]: covered when a scene opens it or docs/UI-COVERAGE.md names it."""
    text = " ".join(" ".join(map(str, s.get("setup", []))) + " " + " ".join(s.get("tags", []))
                    + " " + str(s.get("pose", "")) for s in catalog.load())
    cov = (ROOT / "docs/UI-COVERAGE.md")
    doc = cov.read_text(encoding="utf-8") if cov.exists() else ""
    found = []
    for f in JAVA.rglob("*.java"):
        if "/autotest/" in f.as_posix():  # test-only screens (the showcase) are tools, not game UI
            continue
        t = f.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r"class (\w+)\s+extends\s+(Screen|AbstractContainerScreen<[^>]*>|\w+Screen)\b", t):
            name = m.group(1)
            found.append((name, f.relative_to(ROOT).as_posix(), name in text or name in doc))
    return sorted(set(found))


def known_ids():
    idx = catalog.subject_index()
    ids = set()
    for section, v in idx.items():
        if isinstance(v, list):
            ids |= {r["id"] if isinstance(r, dict) else r for r in v if isinstance(r, (dict, str))}
        elif isinstance(v, dict):
            for vals in v.values():
                ids |= set(vals)
    return ids


def diff_ids(ref="HEAD", staged=False):
    """Ids a diff adds in Java that the subject index does not know: [(id, file)]."""
    args = ["git", "diff", "-U0"] + (["--cached"] if staged else [ref]) + ["--", "mod/src/main/java"]
    diff = subprocess.run(args, cwd=ROOT, capture_output=True, text=True).stdout
    known, out, cur = known_ids(), [], ""
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            cur = line[6:]
        elif line.startswith("+") and not line.startswith("+++"):
            for m in ID_IN_CODE.finditer(line):
                if m.group(1) not in known:
                    out.append((m.group(1), cur))
    return sorted(set(out))


def recommend(files, tests, ref="HEAD", staged=False):
    """What a change needs before it is committed: the tests it affects (given), the ids it adds that the subject
    index does not know, the screens it touches (and whether a scene covers them), whether the index is stale."""
    rec = {"files": files, "tests": tests, "newIds": diff_ids(ref, staged), "screens": [], "indexStale": False}
    changed = {Path(f).name for f in files}
    rec["screens"] = [(n, f, c) for n, f, c in screens() if Path(f).name in changed]
    idx = catalog.SUBJECT_INDEX
    java = [f for f in files if f.startswith("mod/src/main/java") and (ROOT / f).exists()]
    rec["indexStale"] = bool(rec["newIds"]) or not idx.exists() or any(
        (ROOT / f).stat().st_mtime > idx.stat().st_mtime for f in java)
    return rec
