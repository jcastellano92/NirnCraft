"""The independent reviewer: a fresh, headless Claude session (Haiku by default) that grades visual results it did not
make. It sees only the evidence images and the claim to check, has read-only tools, and runs in an empty folder (no
project memory, no conversation of the agent that made the fix). Its verdicts move issues; its raw answers are kept
(.work/harness/review/log.jsonl) so every confirmation can be audited.

Two modes:
* claims: "is this fixed?" per issue in needs-review (evidence: the before | after sheet, twin side-by-side, zooms).
* pages: the visual-reviewer pass over a run's agent pages (BROKEN / REGRESSION / CHECK lines), used by the gate.
"""
import json
import re
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STORE = ROOT / ".work/harness"
DIR = STORE / "review"
LOG = DIR / "log.jsonl"
AGENT = ROOT / ".claude/agents/visual-reviewer.md"
MAX_IMAGES = 8          # what one reviewer pass can judge before details are lost
TIMEOUT = 600


def available():
    return shutil.which("claude") is not None


def _instructions():
    t = AGENT.read_text(encoding="utf-8")
    return t.split("---", 2)[2].strip() if t.startswith("---") else t


def _ask(prompt, model, dirs):
    """One headless reviewer session; returns its final text."""
    DIR.mkdir(parents=True, exist_ok=True)
    cmd = [shutil.which("claude"), "-p", "--model", model, "--output-format", "json",
           "--allowedTools", "Read", "Glob"]
    for d in dirs:
        cmd += ["--add-dir", str(d)]
    r = subprocess.run(cmd, input=prompt, cwd=DIR, capture_output=True, text=True, encoding="utf-8",
                       timeout=TIMEOUT)
    if r.returncode:
        raise RuntimeError(f"reviewer failed ({r.returncode}): {(r.stderr or r.stdout)[-400:]}")
    try:
        return json.loads(r.stdout).get("result", "")
    except ValueError:
        return r.stdout


def _log(entry):
    DIR.mkdir(parents=True, exist_ok=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(json.dumps({"at": time.strftime("%Y-%m-%d %H:%M:%S"), **entry}) + "\n")


def claims(items, model="haiku"):
    """items: [{"key", "title", "claim", "images": [paths]}] -> {key: {"verdict": pass|fail|unsure, "reason"}}."""
    out = {}
    for i in range(0, len(items), MAX_IMAGES // 2 or 1):
        batch = items[i:i + MAX_IMAGES // 2]
        lines = []
        for it in batch:
            lines.append(f'- key: {it["key"]}\n  test: {it["title"]}\n  claim to check: {it["claim"]}\n  images: '
                         + ", ".join(str(p) for p in it["images"]))
        prompt = (
            "You are an independent visual reviewer for NirnCraft (a Minecraft 26.3 modpack). You did not make these "
            "changes and you have no stake in them. Judge only from the images; never assume a fix worked.\n\n"
            + _instructions() + "\n\n"
            "For each item below, Read its images (a sheet labelled before | after | change, a lab | live side-by-side, "
            "or a zoom) and decide whether the claim holds. A panel labelled NOT COMPARABLE is from another view or "
            "time: do not draw conclusions from it. If the images cannot show it, answer unsure.\n\n"
            + "\n".join(lines) + "\n\n"
            "Answer with one JSON array only, no prose: "
            '[{"key": "...", "verdict": "pass|fail|unsure", "reason": "what you saw, where in the frame"}]')
        text = _ask(prompt, model, {Path(p).parent for it in batch for p in it["images"]})
        m = re.search(r"\[.*\]", text, re.S)
        try:
            verdicts = json.loads(m.group(0)) if m else []
        except ValueError:
            verdicts = []
        for it in batch:
            v = next((x for x in verdicts if x.get("key") == it["key"]), None)
            out[it["key"]] = v or {"verdict": "unsure", "reason": "no verdict in the reviewer's answer"}
        _log({"mode": "claims", "model": model, "items": batch, "answer": text[-4000:]})
    return out


def pages(agent_dir, model="haiku", context=""):
    """The visual-reviewer pass over a run's agent pages -> [(kind, test, variant, what)] (kind BROKEN|REGRESSION|CHECK)."""
    agent_dir = Path(agent_dir)
    if not (agent_dir / "index.txt").exists():
        return []
    prompt = (_instructions() + f"\n\nAgent folder: {agent_dir}\n" + (f"What changed: {context}\n" if context else "")
              + "You are independent of whoever made the change. Answer in the format above only.")
    text = _ask(prompt, model, [agent_dir, STORE / "zoom"])
    found, ok = parse_pages(text)
    LAST_PAGES.clear()
    LAST_PAGES.update(ok=ok, findings=len(found), answer=text.strip()[-600:])
    _log({"mode": "pages", "model": model, "dir": str(agent_dir), "answer": text[-4000:]})
    return found


def parse_pages(text):
    """A pages answer -> ([(kind, test, variant, what)], the count it says look right or None when it did not say)."""
    found = []
    ok = None
    for line in text.splitlines():
        m = re.match(r"\s*(BROKEN|REGRESSION|CHECK)\s+(\S+)\s*(?:\[([^\]]+)\])?\s*:\s*(.*)", line)
        if m:
            found.append((m.group(1), m.group(2), m.group(3) or "", m.group(4).strip()))
        m = re.match(r"\s*OK\s+(\d+)", line)
        if m:
            ok = int(m.group(1))
    return found, ok


# what the last pages() pass reported beyond its findings: {"ok": n tests it says look right (None: it did not say),
# "findings": n, "answer": its last words}; the gate logs coverage from it
LAST_PAGES = {}
