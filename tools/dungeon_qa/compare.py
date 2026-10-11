"""Before/after table of two dungeon QA runs (tools/dungeon_qa/<tag>/dungeons.csv).

Usage: py tools/dungeon_qa/compare.py [before] [after]  (default: before after)
Writes tools/dungeon_qa/before_after.md and prints it.
"""
import csv
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# (label, column, kind): kind = mean | pct (share of dungeons with a value > 0) | sum
METRICS = [
    ("dungeons", None, "count"),
    ("blockers", "blockers", "blockers"),
    ("rooms reached %", None, "reached"),
    ("critical path (blocks)", "path_blocks", "mean"),
    ("walk to boss (s)", "walk_seconds", "mean"),
    ("vertical travel on path", "path_vertical", "mean"),
    ("enemies on path", "enemies_on_path", "mean"),
    ("ramp to boss (Spearman)", "ramp", "mean"),
    ("boss threat / strongest room", "boss_ratio", "mean"),
    ("boss present %", "boss_present", "pct"),
    ("elite guard %", "elites", "pct"),
    ("ambush beat %", "ambush", "pct"),
    ("traps per dungeon", "traps", "mean"),
    ("dungeons with a trap %", "traps", "pct"),
    ("dark walkable %", "dark_pct", "mean"),
    ("mean light", "mean_light", "mean"),
    ("loot value (gold)", "loot_total", "mean"),
    ("boss chest value", "loot_boss", "mean"),
    ("side branch with reward %", "side_rewards", "pct"),
    ("empty dead ends", "dead_ends_empty", "mean"),
    ("loop-back exit %", "loop_back", "pct"),
    ("exit / way-in ratio", "exit_ratio", "mean"),
    ("extra cycles", "cycles", "mean"),
    ("foreshadowing window %", "foreshadow", "pct"),
    ("journals & notes", "notes", "mean"),
    ("set-piece rooms", "setpiece_rooms", "mean"),
    ("layout similarity (nearest)", "sim_layout", "mean"),
    ("content similarity (nearest)", "sim_content", "mean"),
    ("design checklist (0-9)", "design_score", "mean"),
]


def load(tag):
    with open(os.path.join(HERE, tag, "dungeons.csv"), encoding="utf-8") as f:
        return list(csv.DictReader(f))


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def stat(rows, col, kind):
    if kind == "count":
        return len(rows)
    if kind == "blockers":
        return sum(len([b for b in r["blockers"].split("; ") if b]) for r in rows)
    if kind == "reached":
        tot = sum(num(r["rooms"]) or 0 for r in rows)
        return 100.0 * sum(num(r["rooms_reached"]) or 0 for r in rows) / max(1, tot)
    vals = [num(r.get(col)) for r in rows]
    vals = [v for v in vals if v is not None and (v >= 0 or col == "ramp")]
    if not vals:
        return None
    if kind == "pct":
        return 100.0 * sum(1 for v in vals if v > 0) / len(vals)
    return sum(vals) / len(vals)


def fmt(v):
    if v is None:
        return "-"
    return f"{v:.0f}" if abs(v) >= 100 else f"{v:.2f}" if abs(v) < 1 else f"{v:.1f}"


def main():
    before, after = (sys.argv[1:3] + ["before", "after"])[:2] if len(sys.argv) > 1 else ("before", "after")
    b, a = load(before), load(after)
    themes = sorted({r["theme"] for r in b} | {r["theme"] for r in a})
    out = [f"# Dungeon QA: {before} vs {after}", "", "## All dungeons", "", "| metric | before | after |", "|---|---:|---:|"]
    for label, col, kind in METRICS:
        out.append(f"| {label} | {fmt(stat(b, col, kind))} | {fmt(stat(a, col, kind))} |")
    out += ["", "## Per theme (before -> after)", ""]
    keys = [m for m in METRICS if m[0] in ("blockers", "critical path (blocks)", "ramp to boss (Spearman)", "dungeons with a trap %",
                                         "dark walkable %", "side branch with reward %", "loop-back exit %", "journals & notes",
                                         "set-piece rooms", "design checklist (0-9)")]
    out.append("| theme | " + " | ".join(k[0] for k in keys) + " |")
    out.append("|---|" + "---:|" * len(keys))
    for t in themes:
        bt = [r for r in b if r["theme"] == t]
        at = [r for r in a if r["theme"] == t]
        cells = [f"{fmt(stat(bt, c, k))} -> {fmt(stat(at, c, k))}" for _, c, k in keys]
        out.append(f"| {t} | " + " | ".join(cells) + " |")
    text = "\n".join(out) + "\n"
    with open(os.path.join(HERE, "before_after.md"), "w", encoding="utf-8") as f:
        f.write(text)
    print(text)


if __name__ == "__main__":
    main()
