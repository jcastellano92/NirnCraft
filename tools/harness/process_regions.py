"""Read-only virtual-address-space census of one process (VirtualQueryEx / QueryWorkingSetEx).

Private commit is what the job limit charges. This separates commit that is resident from commit that was only
reserved/charged (typical of driver and allocator over-commit), and names the largest regions by type, so growth
can be attributed to a kind of allocation before looking for its owner. No process control; the handle is closed.
"""
import ctypes as c
from ctypes import wintypes as w
import os
import time
from functools import lru_cache

MEM_COMMIT = 0x1000
TYPES = {0x20000: 'private', 0x40000: 'mapped', 0x1000000: 'image'}
PAGE = 4096
# private committed regions by size (a leak made of many mid-size blocks never shows in 'largest')
BUCKETS = [('<1MiB', 1 << 20), ('1-16MiB', 16 << 20), ('16-256MiB', 256 << 20), ('>=256MiB', 1 << 62)]


class MemInfo(c.Structure):
    _fields_ = [('base', c.c_size_t), ('allocBase', c.c_size_t), ('allocProtect', w.DWORD), ('pad', w.DWORD),
                ('size', c.c_size_t), ('state', w.DWORD), ('protect', w.DWORD), ('type', w.DWORD), ('pad2', w.DWORD)]


class WsEx(c.Structure):
    _fields_ = [('address', c.c_void_p), ('attributes', c.c_size_t)]


@lru_cache(maxsize=1)
def api():
    if os.name != 'nt':
        raise OSError('Windows virtual memory queries unavailable')
    k = c.WinDLL('kernel32', use_last_error=True)
    p = c.WinDLL('psapi', use_last_error=True)
    k.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
    k.OpenProcess.restype = w.HANDLE
    k.CloseHandle.argtypes = [w.HANDLE]
    k.VirtualQueryEx.argtypes = [w.HANDLE, c.c_void_p, c.POINTER(MemInfo), c.c_size_t]
    k.VirtualQueryEx.restype = c.c_size_t
    k.GetProcessHandleCount.argtypes = [w.HANDLE, c.POINTER(w.DWORD)]
    k.GetCurrentProcess.restype = w.HANDLE
    p.QueryWorkingSetEx.argtypes = [w.HANDLE, c.c_void_p, w.DWORD]
    return k, p


def open_handle_count():
    k, _ = api()
    n = w.DWORD()
    k.GetProcessHandleCount(k.GetCurrentProcess(), c.byref(n))
    return n.value


def resident_bytes(handle, base, size, p, limit_pages=1 << 14):
    """Resident bytes of [base, base+size) by sampling every page (bounded: stride grows for huge regions)."""
    pages = size // PAGE
    stride = max(1, pages // limit_pages)
    count = (pages + stride - 1) // stride
    hit = 0
    chunk = 1 << 16
    for first in range(0, count, chunk):
        n = min(chunk, count - first)
        buf = (WsEx * n)()
        for i in range(n):
            buf[i].address = base + (first + i) * stride * PAGE
        if not p.QueryWorkingSetEx(handle, buf, c.sizeof(buf)):
            return None
        hit += sum(1 for e in buf if e.attributes & 1)
    return hit * stride * PAGE


def snapshot(pid, min_region=256 << 20, top=12):
    started = time.time()
    try:
        k, p = api()
    except OSError as exc:
        return {'error': str(exc)}
    h = k.OpenProcess(0x0400 | 0x0010, False, int(pid))  # QUERY_INFORMATION | VM_READ
    if not h:
        return {'pid': int(pid), 'error': 'OpenProcess failed', 'winerror': c.get_last_error()}
    try:
        totals = {}
        big = []
        allocs = {}
        buckets = {name: {'regions': 0, 'committedBytes': 0} for name, _ in BUCKETS}
        address = 0
        info = MemInfo()
        while k.VirtualQueryEx(h, address, c.byref(info), c.sizeof(info)):
            size = int(info.size)
            if info.state == MEM_COMMIT:
                kind = TYPES.get(info.type, 'other')
                t = totals.setdefault(kind, {'committedBytes': 0, 'regions': 0, 'largestBytes': 0})
                t['committedBytes'] += size
                t['regions'] += 1
                t['largestBytes'] = max(t['largestBytes'], size)
                if kind == 'private':
                    a = allocs.setdefault(int(info.allocBase), {'allocBase': int(info.allocBase), 'committedBytes': 0, 'regions': 0})
                    a['committedBytes'] += size
                    a['regions'] += 1
                    for name, limit in BUCKETS:
                        if size < limit:
                            buckets[name]['regions'] += 1
                            buckets[name]['committedBytes'] += size
                            break
                if size >= min_region:
                    big.append({'base': int(info.base), 'size': size, 'type': kind, 'protect': int(info.protect)})
            address = int(info.base) + size
            if address >= 1 << 47 or size == 0:
                break
        big.sort(key=lambda r: -r['size'])
        big = big[:top]
        by_alloc = sorted(allocs.values(), key=lambda a: -a['committedBytes'])[:top]
        for r in big:
            r['residentBytes'] = resident_bytes(h, r['base'], r['size'], p)
        return {'pid': int(pid), 'epoch': time.time(), 'totals': totals, 'largest': big, 'byAllocation': by_alloc, 'sizeBuckets': buckets, 'minRegionBytes': min_region,
                'elapsedSeconds': round(time.time() - started, 3)}
    finally:
        k.CloseHandle(h)


def summarize(snap):
    if 'error' in snap:
        return 'regions unavailable: ' + snap['error']
    mib = lambda n: '%.0f MiB' % (n / 1048576)
    parts = ['%s %s in %d' % (kind, mib(v['committedBytes']), v['regions']) for kind, v in sorted(snap['totals'].items())]
    big = ['%s %s (resident %s)' % (r['type'], mib(r['size']), mib(r['residentBytes']) if r['residentBytes'] is not None else '?')
           for r in snap['largest']]
    buckets = ', '.join('%s %s in %d' % (n, mib(v['committedBytes']), v['regions']) for n, v in snap.get('sizeBuckets', {}).items())
    return 'committed: ' + '; '.join(parts) + ' | private by size: ' + buckets + ' | largest: ' + ', '.join(big)


if __name__ == '__main__':
    import argparse
    import json
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('pid', type=int)
    ap.add_argument('--min-region-mib', type=int, default=64)
    args = ap.parse_args()
    s = snapshot(args.pid, args.min_region_mib << 20)
    print(summarize(s))
    print(json.dumps(s))
