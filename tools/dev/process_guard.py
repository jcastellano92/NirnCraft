"""Cooperative, nonblocking OS locks. Persistent files are never stale ownership."""
from contextlib import contextmanager
import errno
import json
import os
from pathlib import Path
import re
import threading

if os.name == "nt":
    import msvcrt
else:
    import fcntl

_held = {}
_registry_lock = threading.Lock()


class GuardBusy(RuntimeError):
    """Another cooperative tool invocation holds this resource."""


def _owner(value):
    # Labels only: never record command lines, environment, launcher arguments or tokens.
    return re.sub(r"[^A-Za-z0-9_.: -]", "?", str(value))[:80].strip() or "unknown"


def _description(info):
    pid = info.get("pid")
    pid = pid if type(pid) is int and 0 < pid < 2**32 else "unknown"
    return f"Resource busy: reported owner={_owner(info.get('owner', 'unknown'))}, PID={pid}; no work started"


def _metadata(handle):
    try:
        # Windows denies reads of the locked byte; diagnostics start after it.
        handle.seek(1)
        info = json.loads(handle.read(1024).decode("ascii"))
        return info if isinstance(info, dict) else {}
    except (OSError, ValueError, UnicodeError):
        return {}


def _lock(handle, unlock=False):
    handle.seek(0)
    if os.name == "nt":
        # Byte-range locks can extend beyond EOF, including a newly created file.
        msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK if unlock else msvcrt.LK_NBLCK, 1)
    else:
        fcntl.flock(handle.fileno(), fcntl.LOCK_UN if unlock else fcntl.LOCK_EX | fcntl.LOCK_NB)


@contextmanager
def process_guard(path, owner):
    """Hold an OS lock until exit; fail immediately on contention.

    Nesting on the same thread is supported so public helpers and their CLI can
    share a guard. Owner metadata is diagnostic only, and may lag acquisition.
    Never unlink/replace the lock file, test a PID to unlock it, or force release.
    The OS releases the handle on process death or reboot.
    """
    path = Path(path).resolve()
    key = os.path.normcase(str(path))
    identity = (os.getpid(), threading.get_ident())
    with _registry_lock:
        held = _held.get(key)
        if held is not None:
            if held["identity"] != identity:
                raise GuardBusy(_description(held["info"]))
            held["depth"] += 1
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            descriptor = os.open(path, os.O_RDWR | os.O_CREAT | getattr(os, "O_BINARY", 0), 0o600)
            os.set_inheritable(descriptor, False)
            handle = os.fdopen(descriptor, "r+b", buffering=0)
            try:
                _lock(handle)
            except OSError as exc:
                info = _metadata(handle)
                handle.close()
                if exc.errno in (errno.EACCES, errno.EAGAIN, errno.EDEADLK):
                    raise GuardBusy(_description(info)) from None
                raise
            info = {"owner": _owner(owner), "pid": os.getpid()}
            try:
                handle.seek(1)
                handle.write(json.dumps(info, ensure_ascii=True).encode("ascii"))
                handle.truncate()
            except BaseException:
                handle.close()
                raise
            held = {"handle": handle, "info": info, "identity": identity, "depth": 1}
            _held[key] = held
    try:
        yield
    finally:
        with _registry_lock:
            held["depth"] -= 1
            if held["depth"] == 0:
                try:
                    _lock(held["handle"], unlock=True)
                finally:
                    held["handle"].close()
                    del _held[key]
