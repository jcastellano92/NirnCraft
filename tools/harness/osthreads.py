"""Per-thread CPU of a running process from outside it (Windows): every native thread, the JVM's own and the driver's.

The JVM's ThreadMXBean (AtlasRoute's `cpu` block) names Java threads but cannot see the graphics driver's compile
threads or the native generator's; this samples the OS's own thread times (GetThreadTimes) once a second from the
harness, so it never perturbs the game, and names each thread by its description (Java names its threads) or else by the
module its start address lies in (nvoglv64.dll, atlas_core.dll, jvm.dll ...). Heavy CPU during a test then always has a
named cause.

    s = OsThreads(pid); s.start(); ...; s.stop(); s.report(phase_of)   # phase_of(epoch_ms) -> phase name
"""
import ctypes
import re
import threading
import time
from collections import defaultdict
from ctypes import wintypes as w

k32 = ctypes.WinDLL("kernel32", use_last_error=True)
psapi = ctypes.WinDLL("psapi", use_last_error=True)
ntdll = ctypes.WinDLL("ntdll")

k32.CreateToolhelp32Snapshot.restype = ctypes.c_void_p
k32.CreateToolhelp32Snapshot.argtypes = [w.DWORD, w.DWORD]
k32.OpenThread.restype = ctypes.c_void_p
k32.OpenThread.argtypes = [w.DWORD, w.BOOL, w.DWORD]
k32.OpenProcess.restype = ctypes.c_void_p
k32.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
k32.CloseHandle.argtypes = [ctypes.c_void_p]
k32.GetThreadTimes.argtypes = [ctypes.c_void_p] + [ctypes.POINTER(w.FILETIME)] * 4
k32.Thread32First.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
k32.Thread32Next.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
k32.LocalFree.argtypes = [ctypes.c_void_p]
psapi.EnumProcessModulesEx.argtypes = [ctypes.c_void_p, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD), w.DWORD]
psapi.GetModuleBaseNameW.argtypes = [ctypes.c_void_p, ctypes.c_void_p, w.LPWSTR, w.DWORD]
k32.GetThreadDescription.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)]
k32.GetThreadDescription.restype = ctypes.c_long
ntdll.NtQueryInformationThread.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_ulong, ctypes.c_void_p]
ntdll.NtQueryInformationThread.restype = ctypes.c_long


class THREADENTRY32(ctypes.Structure):
    _fields_ = [("dwSize", w.DWORD), ("cntUsage", w.DWORD), ("th32ThreadID", w.DWORD), ("th32OwnerProcessID", w.DWORD),
                ("tpBasePri", w.LONG), ("tpDeltaPri", w.LONG), ("dwFlags", w.DWORD)]


class MODULEINFO(ctypes.Structure):
    _fields_ = [("base", ctypes.c_void_p), ("size", w.DWORD), ("entry", ctypes.c_void_p)]


psapi.GetModuleInformation.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(MODULEINFO), w.DWORD]


def _ms(ft):
    return ((ft.dwHighDateTime << 32) | ft.dwLowDateTime) / 10000.0


class OsThreads:
    def __init__(self, pid, every=1.0):
        self.pid, self.every = int(pid), every
        self.samples = []          # (epoch_ms, {tid: cpu_ms})
        self.names = {}            # tid -> name
        self._modules, self._modules_at = [], 0.0
        self._stop = threading.Event()
        self._thread = None

    # ---- naming
    def _load_modules(self, hproc):
        if time.monotonic() - self._modules_at < 15 and self._modules:
            return
        self._modules_at = time.monotonic()
        arr = (ctypes.c_void_p * 2048)()
        need = w.DWORD()
        if not psapi.EnumProcessModulesEx(hproc, arr, ctypes.sizeof(arr), ctypes.byref(need), 3):
            return
        mods = []
        for i in range(min(2048, need.value // ctypes.sizeof(ctypes.c_void_p))):
            info = MODULEINFO()
            if psapi.GetModuleInformation(hproc, arr[i], ctypes.byref(info), ctypes.sizeof(info)):
                name = ctypes.create_unicode_buffer(260)
                psapi.GetModuleBaseNameW(hproc, arr[i], name, 260)
                mods.append((info.base or 0, (info.base or 0) + info.size, name.value))
        self._modules = mods

    def _name(self, hthread, hproc, tid):
        desc = ctypes.c_void_p()
        text = ""
        try:
            if k32.GetThreadDescription(ctypes.c_void_p(hthread), ctypes.byref(desc)) >= 0 and desc.value:
                text = ctypes.wstring_at(desc.value)
                k32.LocalFree(desc)
        except (AttributeError, OSError):
            pass
        if text:
            return re.sub(r"\d+", "N", text)
        addr = ctypes.c_void_p()
        if hproc and ntdll.NtQueryInformationThread(ctypes.c_void_p(hthread), 9, ctypes.byref(addr), 8, None) == 0 and addr.value:
            self._load_modules(hproc)
            for lo, hi, name in self._modules:
                if lo <= addr.value < hi:
                    return f"native in {name}"
        return "native thread"

    # ---- one reading
    def sample(self):
        snap = k32.CreateToolhelp32Snapshot(4, 0)
        out = {}
        if not snap or snap == ctypes.c_void_p(-1).value:
            return out
        hproc = k32.OpenProcess(0x0410, False, self.pid)
        try:
            e = THREADENTRY32()
            e.dwSize = ctypes.sizeof(e)
            ok = k32.Thread32First(snap, ctypes.byref(e))
            while ok:
                if e.th32OwnerProcessID == self.pid:
                    h = k32.OpenThread(0x0800 | 0x0040, False, e.th32ThreadID)
                    if not h:
                        h = k32.OpenThread(0x0800, False, e.th32ThreadID)
                    if h:
                        c, x, kt, ut = w.FILETIME(), w.FILETIME(), w.FILETIME(), w.FILETIME()
                        if k32.GetThreadTimes(h, ctypes.byref(c), ctypes.byref(x), ctypes.byref(kt), ctypes.byref(ut)):
                            out[e.th32ThreadID] = _ms(kt) + _ms(ut)
                            if e.th32ThreadID not in self.names:
                                self.names[e.th32ThreadID] = self._name(h, hproc, e.th32ThreadID)
                        k32.CloseHandle(h)
                ok = k32.Thread32Next(snap, ctypes.byref(e))
        finally:
            k32.CloseHandle(snap)
            if hproc:
                k32.CloseHandle(hproc)
        return out

    # ---- the sampler thread
    def _run(self):
        while not self._stop.is_set():
            try:
                self.samples.append((time.time() * 1000.0, self.sample()))
            except Exception:  # noqa: BLE001  a reading that failed is skipped
                pass
            self._stop.wait(self.every)

    def start(self):
        self._thread = threading.Thread(target=self._run, daemon=True, name="osthreads")
        self._thread.start()
        return self

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join(5)
        try:
            self.samples.append((time.time() * 1000.0, self.sample()))
        except Exception:  # noqa: BLE001
            pass

    # ---- booking
    def report(self, phase_of, top=12):
        """{phase: {wallMs, cpuMs, cores, top: [{name, ms, share}]}}: each interval's CPU booked to the phase of its end."""
        books = defaultdict(lambda: {"wallMs": 0.0, "cpuMs": 0.0, "by": defaultdict(float)})
        for (t0, a), (t1, b) in zip(self.samples, self.samples[1:]):
            ph = phase_of(t1)
            bk = books[ph]
            bk["wallMs"] += t1 - t0
            for tid, cpu in b.items():
                d = cpu - a[tid] if tid in a else cpu  # a thread new in this interval counts from zero
                if d > 0:
                    bk["cpuMs"] += d
                    bk["by"][self.names.get(tid, "?")] += d
        out = {}
        for ph, bk in books.items():
            rows = sorted(bk["by"].items(), key=lambda kv: -kv[1])[:top]
            out[ph] = {"wallMs": round(bk["wallMs"]), "cpuMs": round(bk["cpuMs"]),
                       "cores": round(bk["cpuMs"] / bk["wallMs"], 2) if bk["wallMs"] else 0,
                       "top": [{"name": n, "ms": round(ms), "share": round(ms / bk["cpuMs"], 3) if bk["cpuMs"] else 0}
                               for n, ms in rows]}
        return out
