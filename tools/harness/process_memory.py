"""Read-only Windows process/job commit samples; no subprocesses or process control.

Private bytes are CPU committed memory, not Vulkan device allocations or Java heap usage.
Every opened process handle is closed before returning, including failed queries.
"""
import ctypes as c
from ctypes import wintypes as w
import os
from pathlib import PureWindowsPath
import time
from functools import lru_cache


class Counters(c.Structure):
    _fields_ = [("cb", w.DWORD), ("faults", w.DWORD)] + [
        (name, c.c_size_t) for name in ("peakWorking", "working", "peakPaged", "paged",
                                      "peakNonpaged", "nonpaged", "pagefile", "peakPagefile", "private")]


class BasicLimit(c.Structure):
    _fields_ = [("processTime", c.c_longlong), ("jobTime", c.c_longlong), ("flags", w.DWORD),
                ("minWorking", c.c_size_t), ("maxWorking", c.c_size_t), ("active", w.DWORD),
                ("affinity", c.c_size_t), ("priority", w.DWORD), ("scheduling", w.DWORD)]


class JobLimit(c.Structure):
    _fields_ = [("basic", BasicLimit), ("io", c.c_ulonglong * 6)] + [
        (name, c.c_size_t) for name in ("processLimit", "jobLimit", "peakProcess", "peakJob")]


class ProcessIds(c.Structure):
    _fields_ = [("assigned", w.DWORD), ("count", w.DWORD), ("ids", c.c_size_t * 4096)]


class SystemMemory(c.Structure):
    _fields_ = [("length", w.DWORD), ("load", w.DWORD)] + [
        (name, c.c_ulonglong) for name in ("totalPhys", "availPhys", "totalCommit", "availCommit",
                                         "totalVirtual", "availVirtual", "extendedVirtual")]


@lru_cache(maxsize=1)
def windows_api():
    if os.name != "nt":
        raise OSError("Windows process/job memory accounting unavailable")
    k = c.WinDLL("kernel32", use_last_error=True)
    p = c.WinDLL("psapi", use_last_error=True)
    k.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
    k.OpenProcess.restype = w.HANDLE
    k.CloseHandle.argtypes = [w.HANDLE]
    k.QueryFullProcessImageNameW.argtypes = [w.HANDLE, w.DWORD, w.LPWSTR, c.POINTER(w.DWORD)]
    k.GetProcessHandleCount.argtypes = [w.HANDLE, c.POINTER(w.DWORD)]
    k.QueryInformationJobObject.argtypes = [w.HANDLE, c.c_int, c.c_void_p, w.DWORD, c.c_void_p]
    k.OpenJobObjectW.argtypes = [w.DWORD, w.BOOL, w.LPCWSTR]
    k.OpenJobObjectW.restype = w.HANDLE
    k.GlobalMemoryStatusEx.argtypes = [c.POINTER(SystemMemory)]
    p.GetProcessMemoryInfo.argtypes = [w.HANDLE, c.POINTER(Counters), w.DWORD]
    return k, p


def process_row(pid, api):
    k, p = api
    h = k.OpenProcess(0x1000 | 0x10, False, int(pid))  # QUERY_LIMITED_INFORMATION | VM_READ
    if not h:
        return {"pid": int(pid), "error": "OpenProcess failed", "winerror": c.get_last_error()}
    try:
        counters = Counters()
        counters.cb = c.sizeof(counters)
        if not p.GetProcessMemoryInfo(h, c.byref(counters), counters.cb):
            return {"pid": int(pid), "error": "GetProcessMemoryInfo failed", "winerror": c.get_last_error()}
        image = c.create_unicode_buffer(32768)
        length, handles = w.DWORD(len(image)), w.DWORD()
        named = k.QueryFullProcessImageNameW(h, 0, image, c.byref(length))
        counted = k.GetProcessHandleCount(h, c.byref(handles))
        return {"pid": int(pid), "name": PureWindowsPath(image.value).name if named else "unknown",
                "privateBytes": counters.private, "workingSetBytes": counters.working,
                "peakCommitBytes": counters.peakPagefile,
                "handles": handles.value if counted else None}
    finally:
        k.CloseHandle(h)


def sample(client_pids=()):
    row = {"epoch": time.time(), "samplerPid": os.getpid()}
    try:
        api = windows_api()
        k, _ = api
        info, ids, system = JobLimit(), ProcessIds(), SystemMemory()
        name = os.environ.get('NIRNCRAFT_HEAVY_JOB_NAME')
        hjob = k.OpenJobObjectW(0x0004, False, name) if name else None  # JOB_OBJECT_QUERY only
        job_ok = pids_ok = False
        try:
            if hjob:
                job_ok = bool(k.QueryInformationJobObject(hjob, 9, c.byref(info), c.sizeof(info), None))
                pids_ok = bool(k.QueryInformationJobObject(hjob, 3, c.byref(ids), c.sizeof(ids), None))
        finally:
            if hjob: k.CloseHandle(hjob)
        job_pids = set(int(pid) for pid in ids.ids[:min(ids.count, 4096)]) if pids_ok else set()
        row['job'] = {"available": job_ok, "membersAvailable": pids_ok,
                      "namedScope": bool(hjob), "samplerInJob": os.getpid() in job_pids,
                      "limitBytes": info.jobLimit if job_ok and info.basic.flags & 0x200 else None,
                      "peakCommitBytes": info.peakJob if job_ok else None,
                      "membersComplete": pids_ok and ids.count == ids.assigned}
        clients = set(map(int, client_pids))
        rows = [process_row(pid, api) for pid in sorted(job_pids | clients | {os.getpid()})]
        for item in rows:
            item.update(inJob=item['pid'] in job_pids, client=item['pid'] in clients)
        row['processes'] = rows
        row['job']['externalClientPids'] = [p['pid'] for p in rows if p['client'] and not p['inJob']]
        row['job']['sampledPrivateBytes'] = sum(p.get('privateBytes', 0) for p in rows if p['inJob'])
        system.length = c.sizeof(system)
        if k.GlobalMemoryStatusEx(c.byref(system)):
            row['system'] = {"availablePhysicalBytes": system.availPhys,
                             "availableCommitBytes": system.availCommit}
    except OSError as exc:
        row['error'] = str(exc)
    return row


def pressure(row, fraction=0.85):
    job = row.get('job', {})
    # A sampled sum is not the kernel's instantaneous total; either observed value
    # crossing the reserve ends diagnostic work. Sampling cannot guarantee catching
    # an allocation burst before the Windows cap; the cap remains the final bound.
    observed = max(job.get('sampledPrivateBytes', 0), job.get('peakCommitBytes') or 0)
    limit = job.get('limitBytes')
    return bool(limit and observed >= limit * fraction)


def require_accounting(row):
    job = row.get('job', {})
    if (row.get('error') or not all(job.get(key) for key in ('available', 'membersAvailable', 'membersComplete', 'namedScope', 'samplerInJob'))
            or not job.get('limitBytes') or job.get('peakCommitBytes') is None
            or not row.get('processes') or any(p.get('error') for p in row['processes'] if p.get('inJob') or p.get('client'))):
        raise RuntimeError('job memory accounting incomplete; diagnostic work stopped')
    if job.get('externalClientPids') or any(p.get('client') and not p.get('inJob') for p in row['processes']):
        raise RuntimeError('Minecraft client is outside the capped job; diagnostic work stopped')
