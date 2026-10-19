#!/usr/bin/env python3
"""Dungeon reach gate over the dungeon QA export (tools/dungeon_qa/after/dungeons.csv, written by
/nirncraft-dungeon-qa: com.nirncraft.dungeons.qa.QaDungeon / QaReport). Offline: reads the CSV only.

Per dungeon (one CSV row), each failed rule is one problem:
  reach      rooms_reached == rooms (every room walkable from the entrance)
  pockets    trap_pockets == 0 (no reachable spot without a way back out)
  exit       exit_cost >= 0 (the way out of the boss room exists)
  loop       loop_back == 1 (the exit loops back instead of retracing the whole critical path; --no-loop skips it)
  boss       boss_rooms >= 1 and boss_present == 1 (a boss room, and a boss in it)
  enemies    enemies > 0
  loot       loot_containers > 0 and loot_total > 0
  blockers   the analyzer's own blockers column is empty
Columns are mapped from the header, so added columns do not matter; a missing column is a problem of its own.
Prints the rule totals, the worst offenders (most failed rules first) and `problems N`; exit 1 when N > 0.

    py -3 tools/qa/dungeon_csv_gate.py [--csv FILE] [--worst 15] [--no-loop]
"""
import argparse
import csv
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT = ROOT / "tools" / "dungeon_qa" / "after" / "dungeons.csv"
NEEDED = ["label", "rooms", "rooms_reached", "trap_pockets", "exit_cost", "loop_back", "boss_rooms", "boss_present",
          "enemies", "loot_containers", "loot_total", "blockers"]


def num(v, default=None):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def rules(r, loop):
    """[(rule, detail)] of the failed rules of one row."""
    out = []
    rooms, reached = num(r.get("rooms")), num(r.get("rooms_reached"))
    if rooms is None or reached is None or reached < rooms:
        out.append(("reach", f"{r.get('rooms_reached')}/{r.get('rooms')} rooms reached"))
    if num(r.get("trap_pockets"), 1) > 0:
        out.append(("pockets", f"{r.get('trap_pockets')} trap pockets; {(r.get('warnings') or '').strip()[:150]}"))
    if num(r.get("exit_cost"), -1) < 0:
        out.append(("exit", "no way out of the boss room"))
    if loop and num(r.get("loop_back"), 0) < 1:
        out.append(("loop", f"no loop back (exit ratio {r.get('exit_ratio', '?')})"))
    if num(r.get("boss_rooms"), 0) < 1 or num(r.get("boss_present"), 0) < 1:
        out.append(("boss", f"boss rooms {r.get('boss_rooms')}, boss present {r.get('boss_present')}"))
    if num(r.get("enemies"), 0) <= 0:
        out.append(("enemies", "no enemies"))
    if num(r.get("loot_containers"), 0) <= 0 or num(r.get("loot_total"), 0) <= 0:
        out.append(("loot", f"loot containers {r.get('loot_containers')}, value {r.get('loot_total')}"))
    if (r.get("blockers") or "").strip():
        out.append(("blockers", r["blockers"].strip()[:140]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default=str(DEFAULT))
    ap.add_argument("--worst", type=int, default=15)
    ap.add_argument("--no-loop", action="store_true")
    a = ap.parse_args()
    path = Path(a.csv)
    if not path.is_file():
        print(f"no dungeon QA export at {path} (run /nirncraft-dungeon-qa in game, then copy dungeons.csv)")
        print("problems 1")
        return 1
    with path.open(encoding="utf-8", newline="") as fh:
        reader = csv.DictReader(fh)
        header = reader.fieldnames or []
        rows = list(reader)
    missing = [c for c in NEEDED if c not in header]
    problems = [("columns", "-", f"column {c} missing from the CSV header") for c in missing]
    if not rows:
        problems.append(("rows", "-", "the CSV has no dungeon rows"))
    totals, per = {}, []
    for r in rows:
        failed = rules(r, not a.no_loop)
        for rule, detail in failed:
            totals[rule] = totals.get(rule, 0) + 1
            problems.append((rule, r.get("label", "?"), detail))
        if failed:
            per.append((len(failed), num(r.get("trap_pockets"), 0), r, failed))
    named = sum(1 for r in rows if r.get("category") == "named")
    print(f"dungeon gate: {len(rows)} dungeons ({named} named) from {path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else path}")
    print("rule failures: " + (", ".join(f"{k} {v}" for k, v in sorted(totals.items(), key=lambda kv: -kv[1])) or "none"))
    for c in missing:
        print(f"  column {c} missing")
    if per:
        print(f"worst offenders ({len(per)} dungeons fail a rule):")
        for n, _, r, failed in sorted(per, key=lambda t: (-t[0], -t[1], t[2].get("label", "")))[:a.worst]:
            where = f"{r.get('structure', '')} @ {r.get('x', '?')},{r.get('z', '?')}"
            print(f"  {r.get('label', '?'):28s} {n} rule(s)  {where}")
            for rule, detail in failed:
                print(f"      {rule}: {detail}")
    print(f"problems {len(problems)}")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
