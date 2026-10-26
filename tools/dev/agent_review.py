"""Independent text/image review through Codex or Claude, with no conversation from the author.

NIRNCRAFT_REVIEW_PROVIDER=auto|codex|claude (auto prefers Codex), NIRNCRAFT_REVIEW_MODEL optionally selects a model.
The CLI also accepts Claude's JSON-envelope arguments so heavy.py's existing judge hook can use this adapter.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROVIDERS = ("codex", "claude")


def provider():
    selected = os.environ.get("NIRNCRAFT_REVIEW_PROVIDER", "auto").lower()
    if selected == "auto":
        return next((p for p in PROVIDERS if shutil.which(p)), "codex")
    if selected not in PROVIDERS:
        raise ValueError(f"unknown review provider {selected!r}; choose auto, codex or claude")
    return selected


def available():
    return shutil.which(provider()) is not None


def model_name(model=None):
    return model or os.environ.get("NIRNCRAFT_REVIEW_MODEL") or ("haiku" if provider() == "claude" else "gpt-6.1-terra")


def identity(model=None):
    return f"{provider()}:{model_name(model) or 'default'}"


def _scratch():
    configured = os.environ.get("NIRNCRAFT_AGENT_SCRATCH")
    if configured:
        base = Path(configured)
    elif Path("E:/NirnCraft-scratch").is_dir():
        base = Path("E:/NirnCraft-scratch/agent-review")
    else:
        base = ROOT.parent / ".work/agent-review"
    base.mkdir(parents=True, exist_ok=True)
    return base


def ask(prompt, model=None, dirs=(), images=(), timeout=600):
    """Return only the final answer; failed/empty sessions raise, never masquerade as a verdict.

    Codex receives images as attachments and a read-only sandbox, without project instructions, tools from user
    config, or saved conversation. Claude retains its original Read/Glob allowlist. Scratch is outside the repo.
    No provider is retried implicitly: a rate limit remains visible to the caller.
    """
    backend = provider()
    executable = shutil.which(backend)
    if not executable:
        raise RuntimeError(f"{backend} CLI is not on PATH")
    chosen_model = model_name(model)
    pictures = list(dict.fromkeys(Path(p).resolve() for p in images))
    for p in pictures:
        if not p.is_file():
            raise FileNotFoundError(f"review evidence is missing: {p}")
    with tempfile.TemporaryDirectory(prefix="review-", dir=_scratch()) as wd:
        if backend == "codex":
            answer = Path(wd) / "answer.txt"
            cmd = [executable, "--ask-for-approval", "never", "exec", "--sandbox", "read-only",
                   "--skip-git-repo-check", "--ephemeral", "--ignore-user-config",
                   "-c", "project_doc_max_bytes=0", "--color", "never", "--output-last-message", str(answer)]
            if chosen_model:
                cmd += ["--model", chosen_model]
            for p in pictures:
                cmd += ["--image", str(p)]
            cmd += ["-"]
        else:
            cmd = [executable, "-p", "--model", chosen_model, "--output-format", "json",
                   "--allowedTools", "Read", "Glob"]
            for d in sorted({str(Path(d).resolve()) for d in dirs} | {str(p.parent) for p in pictures}):
                cmd += ["--add-dir", d]
        r = subprocess.run(cmd, input=prompt, cwd=wd, capture_output=True, text=True, encoding="utf-8",
                           timeout=timeout)
        if r.returncode:
            raise RuntimeError(f"{backend} reviewer failed ({r.returncode}): {(r.stderr or r.stdout)[-400:]}")
        if backend == "codex":
            text = answer.read_text(encoding="utf-8") if answer.is_file() else ""
        else:
            try:
                envelope = json.loads(r.stdout)
            except ValueError:
                text = r.stdout
            else:
                if envelope.get("is_error"):
                    raise RuntimeError(f"claude reviewer failed: {str(envelope.get('result', ''))[-400:]}")
                text = envelope.get("result", "")
        if not text.strip():
            raise RuntimeError(f"{backend} reviewer returned no final answer")
        return text


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("-p", action="store_true", help="Claude-compatible stdin mode")
    p.add_argument("--model", help="model for the selected provider")
    p.add_argument("--output-format", choices=["json"], default="json")
    p.add_argument("--legacy-judge", action="store_true",
                   help="ignore heavy.py's Claude model default; use NIRNCRAFT_REVIEW_MODEL for this provider")
    a = p.parse_args(argv)
    try:
        model = a.model
        if a.legacy_judge:
            model = os.environ.get("NIRNCRAFT_GATEKEEPER_MODEL", "gpt-6.1-terra") if provider() == "codex" else None
        text = ask(sys.stdin.read(), model,
                   timeout=int(os.environ.get("NIRNCRAFT_REVIEW_TIMEOUT", "60")))
        print(json.dumps({"result": text, "provider": provider()}))
        return 0
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as e:
        print(f"independent reviewer: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
