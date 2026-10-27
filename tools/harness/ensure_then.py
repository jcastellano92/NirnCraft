"""Install this checkout's core, jar and pack, then run a command: py -3 tools/harness/ensure_then.py <command...>

Several worktrees share one game instance and one atlas-dev core; running both steps in one scheduler job (use
--exclusive for game jobs) stops another checkout's install from replacing the core between them.
"""
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / 'dev'))
sys.path.insert(0, str(HERE.parent))
import game

if __name__ == '__main__':
    game.ensure_current()
    print('ENSURE_CURRENT_OK', flush=True)
    sys.exit(subprocess.run(sys.argv[1:]).returncode)
