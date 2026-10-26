"""The independent reviewer: a fresh, headless Codex or Claude session that grades visual results it did not
make. It sees only the evidence images and the claim to check, has read-only tools, and runs in an empty folder (no
project memory, no conversation of the agent that made the fix). Its verdicts move issues; its raw answers are kept
(.work/harness/review/log.jsonl) so every confirmation can be audited.

Two modes:
* claims: "is this fixed?" per issue in needs-review (evidence: the before | after sheet, twin side-by-side, zooms).
* pages: the visual-reviewer pass over a run's agent pages (BROKEN / REGRESSION / CHECK lines), used by the gate.
* pose: does a capture actually show what its scene is for (subject in view, unblocked, informative)? Scenes with
  `vet_pose` get it after every capture: USELESS fails the capture (a comparison never runs on a useless view),
  WEAK flags it, each with the camera change the reviewer suggests.
"""
import json
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STORE = ROOT / ".work/harness"
DIR = STORE / "review"
LOG = DIR / "log.jsonl"
sys.path.insert(0, str(ROOT / "tools/dev"))
import agent_review  # noqa: E402

AGENT = ROOT / "docs/agents/visual-reviewer.md"
MAX_IMAGES = 8          # what one reviewer pass can judge before details are lost
TIMEOUT = 600


def available():
    return agent_review.available()


def identity(model=None):
    return agent_review.identity(model)


def _instructions():
    t = AGENT.read_text(encoding="utf-8")
    return t.split("---", 2)[2].strip() if t.startswith("---") else t


def _ask(prompt, model, dirs, images=()):
    """One headless reviewer session; returns its final text."""
    return agent_review.ask(prompt, model, dirs, images, TIMEOUT)


def _log(entry):
    DIR.mkdir(parents=True, exist_ok=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(json.dumps({"at": time.strftime("%Y-%m-%d %H:%M:%S"), **entry}) + "\n")


def claims(items, model=None):
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
            "For each item below, inspect its images (a sheet labelled before | after | change, a lab | live side-by-side, "
            "or a zoom) and decide whether the claim holds. A panel labelled NOT COMPARABLE is from another view or "
            "time: do not draw conclusions from it. If the images cannot show it, answer unsure.\n\n"
            + "\n".join(lines) + "\n\n"
            "Answer with one JSON array only, no prose: "
            '[{"key": "...", "verdict": "pass|fail|unsure", "reason": "what you saw, where in the frame"}]')
        text = _ask(prompt, model, {Path(p).parent for it in batch for p in it["images"]},
                    [p for it in batch for p in it["images"]])
        m = re.search(r"\[.*\]", text, re.S)
        try:
            verdicts = json.loads(m.group(0)) if m else []
        except ValueError:
            verdicts = []
        if not isinstance(verdicts, list):
            verdicts = []
        for it in batch:
            matches = [x for x in verdicts if isinstance(x, dict) and x.get("key") == it["key"]]
            v = matches[0] if len(matches) == 1 else None
            if not v or v.get("verdict") not in ("pass", "fail", "unsure") or not isinstance(v.get("reason"), str) or not v["reason"].strip():
                v = {"verdict": "unsure", "reason": "missing, ambiguous or malformed verdict in the reviewer's answer"}
            out[it["key"]] = v
        _log({"mode": "claims", "model": identity(model), "items": batch, "answer": text[-4000:]})
    return out


def pages(agent_dir, model=None, context=""):
    """The visual-reviewer pass over a run's agent pages -> [(kind, test, variant, what)] (kind BROKEN|REGRESSION|CHECK)."""
    LAST_PAGES.clear()
    agent_dir = Path(agent_dir).resolve()
    if not (agent_dir / "index.txt").exists():
        raise FileNotFoundError(f"review evidence index is missing: {agent_dir / 'index.txt'}")
    rows = [line for line in (agent_dir / "index.txt").read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rows:
        raise ValueError(f"review evidence index is empty: {agent_dir / 'index.txt'}")
    by_page = {}
    for line in rows:
        match = re.match(r"(page-\d+) row \d+:", line)
        if not match:
            raise ValueError(f"malformed review index row: {line[:120]}")
        by_page.setdefault(match.group(1), []).append(line)
    found, ok, answers = [], 0, []
    names = list(by_page)
    for start in range(0, len(names), MAX_IMAGES):
        batch = names[start:start + MAX_IMAGES]
        prompt = (_instructions() + f"\n\nAgent folder: {agent_dir}\n" + (f"What changed: {context}\n" if context else "")
                  + "You are independent of whoever made the change. This task is one batch: the index rows below "
                  "are its complete scope. Inspect only these attached pages, without reading other index rows/pages. "
                  "Count only this batch's inspected tests. Answer in the format above only.\n\n"
                  + "\n".join(line for name in batch for line in by_page[name]))
        # Select from the index, not a glob: older runs can leave extra page files in the report folder.
        images = [agent_dir / f"{name}.png" for name in batch]
        text = _ask(prompt, model, [agent_dir, STORE / "zoom"], images)
        findings, clean = parse_pages(text)
        found.extend(findings)
        ok = None if ok is None or clean is None else ok + clean
        answers.append(text.strip()[-600:])
        _log({"mode": "pages", "model": identity(model), "dir": str(agent_dir), "pages": batch, "answer": text[-4000:]})
    LAST_PAGES.update(ok=ok, findings=len(found), answer="\n".join(answers)[-600:])
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


def pose(png, subject, model=None):
    """One capture against what it is meant to show: {"verdict": USEFUL | WEAK | USELESS | None, "fix", "sees"}."""
    png = Path(png)
    prompt = "\n".join([
        f"Inspect this Minecraft screenshot with your image-viewing tool or the attached image: {png}",
        f"It was taken by an automated camera to show: {subject}",
        "Judge only what you can see. Answer in exactly this format:",
        "VERDICT: <USEFUL | WEAK | USELESS>",
        "SEES: <one sentence: what is actually in the picture>",
        "PROBLEMS: <camera inside a wall or building, blocked view, facing the wrong way, too close, too far, only "
        "sky or ground, subject off-frame, too dark; or none>",
        "FIX: <a concrete camera change: turn left/right N degrees, move back/forward/up N blocks, pitch up/down; or none>",
        "Be strict: a subject that is only a small or blocked part of the frame is WEAK; not visible at all is USELESS."])
    try:
        text = _ask(prompt, model, [png.parent], [png])
    except Exception as e:  # noqa: BLE001  no reviewer: the capture is unvetted, said so, never blocked by it
        _log({"mode": "pose", "png": str(png), "subject": subject, "error": str(e)[:200]})
        return {"verdict": None, "fix": "", "sees": "", "error": str(e)[:200]}
    out = parse_pose(text)
    _log({"mode": "pose", "png": str(png), "subject": subject, **out})
    return out


def parse_pose(text):
    """The pose reviewer's answer as fields; an answer without a verdict line is None (unvetted, never a pass)."""
    def line(key):
        m = re.search(rf"^\**{key}\**:\s*(.+)$", text, re.M)
        return m.group(1).strip() if m else ""
    v = line("VERDICT").split()[0].upper().strip("*") if line("VERDICT") else None
    return {"verdict": v if v in ("USEFUL", "WEAK", "USELESS") else None, "fix": line("FIX"), "sees": line("SEES"),
            "problems": line("PROBLEMS")}

