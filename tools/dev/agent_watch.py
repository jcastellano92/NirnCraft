"""Stream harness completions and scheduler/feedback events to a tracked Codex tool session.

py -3 -u tools/dev/agent_watch.py --root <another worktree> --minutes 29
Starts at each log's end (no historic flood). Poll its tool-session output between edits and while jobs run.
"""
import argparse
import json
import os
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[2]


def summary(source, row):
    kind = row.get("event", "")
    if source.startswith("harness:"):
        if kind not in {"run_finished", "test_failed", "test_crashed", "client_crashed", "test_quarantined", "group_skipped",
                        "review_finished"}:
            return None
        fields = {k: row[k] for k in ("test", "counts", "report", "reason", "cause", "items", "verdicts") if k in row}
        return f"{source} {kind} {json.dumps(fields, ensure_ascii=True)}"
    fields = {k: row[k] for k in ("label", "caller", "rc", "run_s", "wait_s", "decision", "reason", "question",
                                  "text", "feedback", "status", "verdict", "log") if k in row}
    return f"{source} {json.dumps(fields or row, ensure_ascii=True)[:1400]}"


class Tail:
    def __init__(self, path):
        self.path = Path(path)
        self.offset = self.path.stat().st_size if self.path.exists() else 0

    def read(self):
        try:
            if self.path.stat().st_size < self.offset:
                self.offset = 0
            rows = []
            with self.path.open("rb") as stream:
                stream.seek(self.offset)
                while True:
                    line = stream.readline()
                    if not line.endswith(b"\n"):
                        break  # a partially written row stays unread until the next poll
                    self.offset = stream.tell()
                    try:
                        row = json.loads(line)
                    except (ValueError, UnicodeDecodeError):
                        continue
                    if isinstance(row, dict):
                        rows.append(row)
            return rows
        except OSError:
            return []


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", action="append", type=Path, default=[])
    p.add_argument("--minutes", type=float, default=29)
    a = p.parse_args()
    locks = Path(os.environ.get("NIRNCRAFT_HEAVY", "E:/NirnCraft-scratch/locks/heavy.py")).parent
    feeds = {f"harness:{r.name}": Tail(r / ".work/harness/events.jsonl") for r in [ROOT, *a.root]}
    feeds.update({name: Tail(locks / name) for name in
                  ("jobs.jsonl", "gate.jsonl", "gate-escalations.jsonl", "process-feedback.jsonl", "process-flags.jsonl")})
    print(f"WATCH armed: {len(feeds)} feeds, {a.minutes:g} min; new events only", flush=True)
    end = time.monotonic() + a.minutes * 60
    while time.monotonic() < end:
        for source, tail in feeds.items():
            for row in tail.read():
                line = summary(source, row)
                if line:
                    print(line, flush=True)
        time.sleep(2)
    print("WATCH expired; re-arm while autonomous work continues", flush=True)


if __name__ == "__main__":
    main()
