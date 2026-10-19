"""Hydrology gate over tools/worldgen/hydrology_report.py (which reports but always exits 0): rivers must run downhill
(no climb over a block) and end somewhere real (the sea, a lake, another river); lakes without an outlet, closed pits,
mountain runoff that never reaches a channel and rivers through places are reported as numbers for before/after.

    py -3 tools/harness/audits/hydrology_gate.py         exit 1 when a river climbs or dead-ends
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ENDS = {"sea", "lake", "river", "join", "ocean"}


def main():
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "hydro.json"
        r = subprocess.run([sys.executable, str(ROOT / "tools/worldgen/hydrology_report.py"), "analyze", "--dir",
                            str(ROOT / "mod/src/main/resources/nirncraft/tamriel"), "--json", str(out)],
                           capture_output=True, text=True)
        if r.returncode or not out.exists():
            print("hydrology_report failed:\n" + (r.stdout + r.stderr)[-1500:])
            return 1
        d = json.loads(out.read_text(encoding="utf-8"))
    bad = []
    for rv in d.get("rivers", []):
        if rv.get("uphill_blocks", 0) > 1.0:
            bad.append(f"{rv['name']}: climbs {rv['uphill_blocks']} blocks over {rv.get('uphill_steps')} step(s)")
        end = str(rv.get("end", ""))
        if end not in ENDS and not end.startswith("river:"):
            bad.append(f"{rv['name']}: ends {rv.get('end')!r} (not the sea, a lake or a river)")
    print(f"rivers {len(d.get('rivers', []))}, lakes {d.get('lakes_total')}, lakes without channel "
          f"{d.get('lakes_without_channel')}, pits {d.get('pits_5cells_3blocks')}, place conflicts "
          f"{sum(len(v) for v in (d.get('place_conflicts') or {}).values()) if isinstance(d.get('place_conflicts'), dict) else d.get('place_conflicts')}, "
          f"mountain runoff to channel {d.get('mountain_runoff_to_channel_share')}")
    print(f"problems {len(bad)}")
    for b in bad:
        print("  " + b)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
