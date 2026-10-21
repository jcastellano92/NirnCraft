"""Where build_263.py puts a checkout's Gradle output: the shared scratch for the main checkout, its own directory for a
git worktree (.claude/worktrees/<name> -> <scratch>/wt/<name>). Every tool that reads built classes, jars or moddev
artifacts asks here instead of assuming <scratch>/build."""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = Path(os.environ.get('NIRNCRAFT_263_SCRATCH', r'E:\NirnCraft-scratch\migration-26.3')).resolve()


def out_dir(root: Path = ROOT, scratch: Path = SCRATCH) -> Path:
    """The directory holding build/ and project-cache/ for this checkout."""
    root = Path(root).resolve()
    return scratch / 'wt' / root.name if root.parent.name == 'worktrees' else scratch


def build_dir(root: Path = ROOT, scratch: Path = SCRATCH) -> Path:
    return out_dir(root, scratch) / 'build'
