"""Run the existing shared heavy-job queue with a portable independent judge.

All arguments pass unchanged to heavy.py, e.g. agent_job.py --status or
agent_job.py --exclusive --loop outer --why "finished step" "worker: game route" -- py -3 tools/harness/harness.py run atlas-route
"""
import json
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def command(args, env):
    heavy = Path(env.get("NIRNCRAFT_HEAVY", "E:/NirnCraft-scratch/locks/heavy.py"))
    if not heavy.is_file():
        raise FileNotFoundError(f"shared job scheduler missing: {heavy}; set NIRNCRAFT_HEAVY, do not bypass the queue")
    child_env = dict(env)
    child_env.setdefault("HEAVY_GATE_CLAUDE", json.dumps(
        [sys.executable, str(HERE / "agent_review.py"), "--legacy-judge"]))
    return [sys.executable, str(heavy), *args], child_env


def main(args=None):
    try:
        cmd, env = command(sys.argv[1:] if args is None else args, os.environ)
        return subprocess.call(cmd, env=env)
    except OSError as e:
        print(f"agent job: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
