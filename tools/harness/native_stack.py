"""Where is a native thread? Suspend it briefly, read its registers, scan the top of its stack for return addresses.

    py -3 tools/harness/native_stack.py <pid> <tid> [depth_bytes]

Prints RIP and every stack word that points into a loaded module's image as module+offset (no symbols needed; resolve
offsets against a linker map). Windows only; the thread resumes at once. Diagnostic for hung shutdowns.
"""
import ctypes
import ctypes.wintypes as wt
import struct
import sys

k32 = ctypes.WinDLL('kernel32', use_last_error=True)
psapi = ctypes.WinDLL('psapi', use_last_error=True)
k32.OpenProcess.restype = wt.HANDLE
k32.OpenThread.restype = wt.HANDLE
k32.SuspendThread.restype = wt.DWORD
k32.ResumeThread.restype = wt.DWORD
k32.OpenProcess.argtypes = [wt.DWORD, wt.BOOL, wt.DWORD]
k32.OpenThread.argtypes = [wt.DWORD, wt.BOOL, wt.DWORD]
k32.SuspendThread.argtypes = [wt.HANDLE]
k32.ResumeThread.argtypes = [wt.HANDLE]
k32.GetThreadContext.argtypes = [wt.HANDLE, ctypes.c_void_p]
k32.ReadProcessMemory.argtypes = [wt.HANDLE, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)]
psapi.EnumProcessModulesEx.argtypes = [wt.HANDLE, ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD), wt.DWORD]
psapi.GetModuleInformation.argtypes = [wt.HANDLE, wt.HMODULE, ctypes.c_void_p, wt.DWORD]
psapi.GetModuleFileNameExW.argtypes = [wt.HANDLE, wt.HMODULE, wt.LPWSTR, wt.DWORD]

PROCESS_ALL = 0x1F0FFF
THREAD_ALL = 0x1F03FF
CONTEXT_FULL = 0x10000B  # AMD64 control | integer | floating point? (0x100001|0x100002|0x100008 -> full)


class MODINFO(ctypes.Structure):
    _fields_ = [('base', ctypes.c_void_p), ('size', wt.DWORD), ('entry', ctypes.c_void_p)]


def modules(hproc):
    arr = (wt.HMODULE * 2048)()
    need = wt.DWORD()
    if not psapi.EnumProcessModulesEx(hproc, arr, ctypes.sizeof(arr), ctypes.byref(need), 3): raise OSError('EnumProcessModulesEx')
    out = []
    for i in range(need.value // ctypes.sizeof(wt.HMODULE)):
        info = MODINFO()
        psapi.GetModuleInformation(hproc, arr[i], ctypes.byref(info), ctypes.sizeof(info))
        name = ctypes.create_unicode_buffer(260)
        psapi.GetModuleFileNameExW(hproc, arr[i], name, 260)
        out.append((info.base or 0, info.size, name.value.rsplit('\\', 1)[-1]))
    return out


def locate(mods, addr):
    for base, size, name in mods:
        if base <= addr < base + size: return f'{name}+0x{addr - base:x}'
    return None


def main():
    pid, tid = int(sys.argv[1]), int(sys.argv[2])
    depth = int(sys.argv[3]) if len(sys.argv) > 3 else 16384
    hp = k32.OpenProcess(PROCESS_ALL, False, pid)
    ht = k32.OpenThread(THREAD_ALL, False, tid)
    if not hp or not ht: raise SystemExit('open failed %d' % ctypes.get_last_error())
    mods = modules(hp)
    buf = ctypes.create_string_buffer(1232 + 16)
    ctx = ctypes.addressof(buf)
    ctx += (-ctx) % 16
    ctypes.memset(ctx, 0, 1232)
    struct.pack_into('<I', (ctypes.c_char * 1232).from_address(ctx), 0x30, CONTEXT_FULL)
    k32.SuspendThread(ht)
    try:
        if not k32.GetThreadContext(ht, ctypes.c_void_p(ctx)): raise SystemExit('GetThreadContext failed %d' % ctypes.get_last_error())
        raw = (ctypes.c_char * 1232).from_address(ctx)
        rip, rsp = struct.unpack_from('<Q', raw, 0xF8)[0], struct.unpack_from('<Q', raw, 0x98)[0]
        data = ctypes.create_string_buffer(depth)
        got = ctypes.c_size_t()
        k32.ReadProcessMemory(hp, ctypes.c_void_p(rsp), data, depth, ctypes.byref(got))
    finally:
        k32.ResumeThread(ht)
    print(f'tid {tid} RIP {locate(mods, rip) or hex(rip)} RSP {rsp:#x}')
    seen = 0
    for i in range(0, got.value - 7, 8):
        w = struct.unpack_from('<Q', data, i)[0]
        where = locate(mods, w)
        if where and not where.lower().startswith(('ntdll', 'kernelbase')):
            print(f'  [rsp+{i:#06x}] {where}')
            seen += 1
            if seen >= 60: break


if __name__ == '__main__':
    main()
