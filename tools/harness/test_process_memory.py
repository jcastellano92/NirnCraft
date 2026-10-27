"""Read-only accounting and handle lifetime checks; no game or GPU."""
import ctypes as c
from ctypes import wintypes as w
import os
import unittest
import process_memory as memory


class MemoryAccountingTests(unittest.TestCase):
    def test_unavailable_or_incomplete_accounting_is_not_a_safe_measurement(self):
        valid = {'job': {'available': True, 'membersAvailable': True, 'membersComplete': True, 'namedScope': True, 'samplerInJob': True,
                         'limitBytes': 100, 'peakCommitBytes': 30},
                 'processes': [{'pid': 1, 'inJob': True, 'privateBytes': 30}]}
        memory.require_accounting(valid)
        for key in ('available', 'membersAvailable', 'membersComplete', 'namedScope', 'samplerInJob', 'limitBytes'):
            with self.assertRaisesRegex(RuntimeError, 'incomplete'):
                memory.require_accounting(dict(valid, job=dict(valid['job'], **{key: False})))
        with self.assertRaisesRegex(RuntimeError, 'incomplete'):
            memory.require_accounting(dict(valid, processes=[{'pid': 1, 'inJob': True, 'error': 'unreadable'}]))
        with self.assertRaisesRegex(RuntimeError, 'outside the capped job'):
            memory.require_accounting(dict(valid, processes=[{'pid': 2, 'inJob': False, 'client': True, 'privateBytes': 30}]))

    def test_pressure_preserves_headroom_and_distinguishes_missing_accounting(self):
        self.assertFalse(memory.pressure({'error': 'unavailable'}))
        for value in ('sampledPrivateBytes', 'peakCommitBytes'):
            self.assertTrue(memory.pressure({'job': {'limitBytes': 100, value: 85}}))
            self.assertFalse(memory.pressure({'job': {'limitBytes': 100, value: 84}}))

    @unittest.skipUnless(os.name == 'nt', 'Windows counters')
    def test_current_process_counters_and_failed_queries_close_all_handles(self):
        api = memory.windows_api()
        k, _ = api
        k.GetCurrentProcess.restype = w.HANDLE
        def handles():
            value = w.DWORD()
            self.assertTrue(k.GetProcessHandleCount(k.GetCurrentProcess(), c.byref(value)))
            return value.value
        before = handles()
        for _ in range(50):
            row = memory.process_row(os.getpid(), api)
            self.assertGreater(row['privateBytes'], 0)
            self.assertGreater(row['workingSetBytes'], 0)
            self.assertNotIn('error', row)
            self.assertIn('error', memory.process_row(0, api))
        self.assertEqual(before, handles(), 'memory sampler must not accumulate process handles')

    @unittest.skipUnless(os.name == 'nt', 'Windows counters')
    def test_actual_job_membership_and_accounting(self):
        row = memory.sample([os.getpid()])
        self.assertNotIn('error', row)
        self.assertGreater(row['system']['availableCommitBytes'], 0)
        own = next(p for p in row['processes'] if p['pid'] == os.getpid())
        self.assertTrue(own['client'])
        self.assertGreater(own['privateBytes'], 0)
        if row['job']['available']:
            memory.require_accounting(row)
            self.assertTrue(row['job']['membersComplete'])
            self.assertTrue(own['inJob'])
            self.assertGreaterEqual(row['job']['peakCommitBytes'], own['privateBytes'])

    @unittest.skipUnless(os.name == 'nt', 'Windows nested launcher accounting')
    def test_named_cap_job_survives_nested_py_launcher_and_closes_query_handles(self):
        # The suite itself runs through py -3 below the scheduler, reproducing the
        # inner-job scope that returned an uncapped current-process-only list.
        self.assertTrue(os.environ.get('NIRNCRAFT_HEAVY_JOB_NAME'), 'run this gate through agent_job')
        row = memory.sample([os.getpid()])
        memory.require_accounting(row)
        self.assertGreaterEqual(len([p for p in row['processes'] if p['inJob']]), 2)
        self.assertEqual(row['job']['limitBytes'], 16 * 2**30, 'CPU gate must measure scheduler cap, not launcher job')
        k, _ = memory.windows_api()
        k.GetCurrentProcess.restype = w.HANDLE
        def handles():
            count = w.DWORD()
            self.assertTrue(k.GetProcessHandleCount(k.GetCurrentProcess(), c.byref(count)))
            return count.value
        before = handles()
        for _ in range(25):
            memory.require_accounting(memory.sample())
        self.assertEqual(before, handles(), 'named job/process query handles must all close')


if __name__ == '__main__':
    unittest.main()
