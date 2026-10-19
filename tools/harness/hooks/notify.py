"""Harness hook: a desktop notification for one event (the event JSON is in HARNESS_EVENT).

    "client_crashed": ["py -3 tools/harness/hooks/notify.py"]     in tools/harness/hooks.json or .work/harness/hooks.json

Windows: a tray balloon through PowerShell (no extra modules); Linux: notify-send when present; otherwise a line on
stdout. run_finished only notifies when something failed, crashed or was skipped.
"""
import json
import os
import shutil
import subprocess
import sys


def text(ev):
    kind = ev.get("event", "")
    if kind == "run_finished":
        c = ev.get("counts", {})
        bad = {k: v for k, v in c.items() if k in ("error", "crash", "fail", "skip")}
        if not bad:
            return None
        return "Harness run finished", ", ".join(f"{v} {k}" for k, v in sorted(c.items(), key=str)) + f"\n{ev.get('report', '')}"
    title = {"client_crashed": "Test client crashed", "test_crashed": "Test crashed", "test_failed": "Test failed",
             "test_quarantined": "Test quarantined", "group_skipped": "Tests skipped",
             "client_restarted": "Test client restarted"}.get(kind, kind)
    body = ev.get("test") or ev.get("group") or ""
    why = ev.get("cause") or ev.get("reason") or ""
    return title, (body + (": " + why if why else ""))[:240]


def main():
    try:
        ev = json.loads(os.environ.get("HARNESS_EVENT", "{}"))
    except ValueError:
        return 0
    msg = text(ev)
    if not msg:
        return 0
    title, body = msg
    if os.name == "nt":
        ps = ("Add-Type -AssemblyName System.Windows.Forms; $n = New-Object System.Windows.Forms.NotifyIcon; "
              "$n.Icon = [System.Drawing.SystemIcons]::Information; $n.Visible = $true; "
              f"$n.ShowBalloonTip(8000, '{title.replace(chr(39), '')}', '{body.replace(chr(39), '')}', 'Info'); "
              "Start-Sleep -Seconds 9; $n.Dispose()")
        subprocess.Popen(["powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", ps],
                         creationflags=0x08000000)  # no window; returns at once
    elif shutil.which("notify-send"):
        subprocess.Popen(["notify-send", title, body])
    else:
        print(f"[harness] {title}: {body}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
