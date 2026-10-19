"""Step trace for the harness: every action batch sent to the game (verbs, duration, outcome, client status after),
launches / closes / restarts, reloads, check and lab commands, crash detection and harness tracebacks, as JSON lines
in .work/harness/trace/run.jsonl (the previous run is kept as previous.jsonl, nothing older).

`harness.py trace <test>` prints what led up to a test's failure, interleaved with the game's log lines of the same
seconds, so an agent can audit where a run went wrong without reading whole logs.
"""
import json
import time
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIR = ROOT / ".work/harness/trace"
RUN = DIR / "run.jsonl"
PREVIOUS = DIR / "previous.jsonl"
_test = {"key": None}
_tail = []  # the current test's steps (kept on its record when it fails)


def start_run(**info):
    DIR.mkdir(parents=True, exist_ok=True)
    if RUN.exists() and RUN.stat().st_size:
        RUN.replace(PREVIOUS)
    step("run_start", **info)


def test(key):
    """Steps after this belong to `key` (None: between tests)."""
    _test["key"] = key
    _tail.clear()


def step(_step, **data):
    rec = {"t": time.time(), "at": time.strftime("%H:%M:%S"), "step": _step, "test": _test["key"], **data}
    try:
        DIR.mkdir(parents=True, exist_ok=True)
        with open(RUN, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, default=str) + "\n")
    except OSError:
        pass
    _tail.append(rec)
    del _tail[:-40]
    return rec


def error(kind, exc=None, **data):
    return step(kind, error=str(exc)[-600:] if exc else None, traceback=traceback.format_exc()[-4000:], **data)


def tail(n=25):
    return [{k: v for k, v in r.items() if k != "t"} for r in _tail[-n:]]


def load(previous=False):
    f = PREVIOUS if previous else RUN
    out = []
    if f.exists():
        for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
            try:
                out.append(json.loads(line))
            except ValueError:
                pass
    return out
