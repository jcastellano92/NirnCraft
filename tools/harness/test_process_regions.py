import ctypes as c
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import process_regions


@unittest.skipUnless(os.name == 'nt', 'Windows virtual memory queries')
class ProcessRegionTests(unittest.TestCase):
    def alloc(self, size):
        k = c.WinDLL('kernel32', use_last_error=True)
        k.VirtualAlloc.restype = c.c_void_p
        k.VirtualAlloc.argtypes = [c.c_void_p, c.c_size_t, c.c_ulong, c.c_ulong]
        k.VirtualFree.argtypes = [c.c_void_p, c.c_size_t, c.c_ulong]
        base = k.VirtualAlloc(None, size, 0x3000, 0x04)  # COMMIT|RESERVE, READWRITE
        self.assertTrue(base)
        self.addCleanup(k.VirtualFree, base, 0, 0x8000)
        return base

    def test_untouched_commit_is_charged_but_not_resident(self):
        size = 256 << 20
        base = self.alloc(size)
        c.memset(base, 1, size // 2)  # touch exactly the first half
        snap = process_regions.snapshot(os.getpid(), min_region=64 << 20)
        self.assertNotIn('error', snap)
        mine = [r for r in snap['largest'] if r['base'] <= base < r['base'] + r['size']]
        self.assertEqual(1, len(mine), snap['largest'])
        r = mine[0]
        self.assertEqual('private', r['type'])
        self.assertGreaterEqual(r['size'], size)
        self.assertGreaterEqual(r['residentBytes'], size // 2)
        self.assertLess(r['residentBytes'], size * 3 // 4)
        self.assertGreaterEqual(snap['totals']['private']['committedBytes'], size)

    def test_totals_cover_whole_address_space_and_handle_is_closed(self):
        before = process_regions.open_handle_count()
        process_regions.snapshot(os.getpid())
        process_regions.snapshot(os.getpid())
        self.assertEqual(before, process_regions.open_handle_count())
        snap = process_regions.snapshot(os.getpid())
        committed = sum(v['committedBytes'] for v in snap['totals'].values())
        self.assertGreater(committed, 1 << 20)

    def test_missing_process_reports_error_not_exception(self):
        self.assertIn('error', process_regions.snapshot(0xFFFFFFF0))

    def test_summary_names_largest_regions_for_the_report(self):
        base = self.alloc(128 << 20)
        c.memset(base, 1, 128 << 20)
        line = process_regions.summarize(process_regions.snapshot(os.getpid(), min_region=64 << 20))
        self.assertIn('private', line)
        self.assertIn('MiB', line)


if __name__ == '__main__':
    unittest.main()
