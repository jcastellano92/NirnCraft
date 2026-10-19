"""Exercise real subprocess locks without starting Gradle, Minecraft or a launcher."""
import contextlib
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
POWERSHELL = shutil.which("powershell") or shutil.which("pwsh")
sys.path.insert(0, str(TOOLS))
from dev.process_guard import GuardBusy, process_guard

WORKER = r"""
import os, sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
from dev.process_guard import GuardBusy, process_guard
try:
    with process_guard(Path(sys.argv[2]), sys.argv[3]):
        if sys.argv[4] == 'try':
            print('acquired', flush=True)
        else:
            Path(sys.argv[5]).write_text(str(os.getpid()))
            sys.stdin.readline()
            if sys.argv[4] == 'crash':
                os._exit(17)
            if sys.argv[4] == 'exception':
                raise RuntimeError('deliberate test exception')
except GuardBusy as exc:
    print(str(exc), flush=True)
    sys.exit(23)
"""


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


build = load("build_263")
live = load("live_world")


class ProcessGuardTest(unittest.TestCase):
    def contender(self, lock, owner="contender"):
        return subprocess.run([sys.executable, "-I", "-c", WORKER, str(TOOLS), str(lock), owner, "try"],
                              capture_output=True, text=True, timeout=5)

    @contextlib.contextmanager
    def holder(self, lock, mode="normal", owner="test holder"):
        marker = lock.with_name(lock.name + ".ready")
        marker.unlink(missing_ok=True)
        child = subprocess.Popen([sys.executable, "-I", "-c", WORKER, str(TOOLS), str(lock), owner, mode, str(marker)],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 5
            while not marker.exists():
                if child.poll() is not None or time.monotonic() >= deadline:
                    self.fail("Python guard holder did not acquire its lock")
                time.sleep(0.02)
            yield child
        finally:
            stdout, stderr = child.communicate(input="release\n", timeout=5)
            expected = {"normal": 0, "exception": 1, "crash": 17}[mode]
            self.assertEqual(expected, child.returncode, stdout + stderr)

    def assert_busy(self, lock, pid=None):
        result = self.contender(lock)
        self.assertEqual(23, result.returncode, result.stdout + result.stderr)
        self.assertIn("Resource busy:", result.stdout)
        if pid is not None:
            self.assertIn("PID=" + str(pid), result.stdout)
        return result.stdout

    def test_subprocess_contention_fails_promptly_without_replacing_owner_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            lock = Path(folder) / "guard.lock"
            with self.holder(lock, owner="build_263.py") as child:
                with lock.open("rb", buffering=0) as source:
                    source.seek(1)
                    original = source.read()
                started = time.monotonic()
                diagnostic = self.assert_busy(lock, child.pid)
                self.assertLess(time.monotonic() - started, 3)
                self.assertIn("owner=build_263.py", diagnostic)
                with lock.open("rb", buffering=0) as source:
                    source.seek(1)
                    self.assertEqual(original, source.read())

    def test_normal_exception_and_unwound_process_death_release_the_os_lock(self):
        with tempfile.TemporaryDirectory() as folder:
            lock = Path(folder) / "guard.lock"
            for mode in ("normal", "exception", "crash"):
                with self.subTest(mode=mode):
                    with self.holder(lock, mode) as child:
                        self.assert_busy(lock, child.pid)
                    self.assertTrue(lock.exists())
                    result = self.contender(lock)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertEqual("acquired", result.stdout.strip())

    def test_existing_metadata_is_not_an_ownership_or_pid_liveness_test(self):
        with tempfile.TemporaryDirectory() as folder:
            lock = Path(folder) / "guard.lock"
            for raw in (b"corrupt old metadata", json.dumps({"pid": os.getpid(), "owner": "old owner"}).encode()):
                lock.write_bytes(b"\0" + raw)
                result = self.contender(lock)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_distinct_resources_and_same_thread_nesting_preserve_ownership(self):
        with tempfile.TemporaryDirectory() as folder:
            lock = Path(folder) / "guard.lock"
            with process_guard(lock, "outer"):
                with process_guard(lock, "inner"):
                    self.assert_busy(lock, os.getpid())
                self.assert_busy(lock, os.getpid())
                self.assertEqual(0, self.contender(lock.with_name("other.lock")).returncode)
            self.assertEqual(0, self.contender(lock).returncode)

    def test_owner_diagnostics_strip_controls_and_never_include_command_lines(self):
        with tempfile.TemporaryDirectory() as folder:
            lock = Path(folder) / "guard.lock"
            with self.holder(lock, owner="test\n\x1b[31m owner") as child:
                diagnostic = self.assert_busy(lock, child.pid)
                self.assertNotIn("\x1b", diagnostic)
                self.assertEqual(1, len(diagnostic.splitlines()))
                self.assertNotIn(str(TOOLS), diagnostic)
                self.assertNotIn("-c", diagnostic)

    def test_another_thread_cannot_enter_or_release_the_owning_threads_guard(self):
        with tempfile.TemporaryDirectory() as folder:
            lock = Path(folder) / "guard.lock"

            def attempt():
                with process_guard(lock, "other thread"):
                    return "unexpectedly acquired"

            with process_guard(lock, "owning thread"), ThreadPoolExecutor(max_workers=1) as pool:
                with self.assertRaises(GuardBusy):
                    pool.submit(attempt).result(timeout=3)
                self.assert_busy(lock, os.getpid())
            self.assertEqual(0, self.contender(lock).returncode)

    def test_build_contention_preserves_log_and_does_not_call_gradle(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            log = root / "build.log"
            log.write_text("preserve previous log")
            with self.holder(root / ".native-build.lock"), \
                    patch.dict(os.environ, {"NIRNCRAFT_263_SCRATCH": str(root), "NIRNCRAFT_JAVA25_HOME": str(root / "java")}), \
                    patch.object(sys, "argv", ["build_263.py", "--log", str(log), "build"]), \
                    patch.object(build.subprocess, "call") as call, contextlib.redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(2, build.main())
                call.assert_not_called()
                self.assertIn("Resource busy", errors.getvalue())
            self.assertEqual("preserve previous log", log.read_text())

    def test_build_holds_guard_through_synchronous_subprocess_and_returns_its_status(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            lock = root / ".native-build.lock"

            def fake_gradle(args, **kwargs):
                self.assert_busy(lock, os.getpid())
                self.assertIn("-PnirncraftBuildDir=" + str(root.resolve() / "build"), args)
                kwargs["stdout"].write(b"mock build output")
                return 9

            with patch.dict(os.environ, {"NIRNCRAFT_263_SCRATCH": str(root), "NIRNCRAFT_JAVA25_HOME": str(root / "java")}), \
                    patch.object(sys, "argv", ["build_263.py", "--log", str(root / "build.log"), "test"]), \
                    patch.object(build.subprocess, "call", side_effect=fake_gradle):
                self.assertEqual(9, build.main())
            self.assertEqual(0, self.contender(lock).returncode)

    def test_python_build_forwards_tasks_offline_and_child_environment_exactly(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            env = {"NIRNCRAFT_263_SCRATCH": str(root), "NIRNCRAFT_JAVA25_HOME": str(root / "java"),
                   "JAVA_HOME": "parent java", "GRADLE_USER_HOME": "parent gradle", "DEBUG": "parent debug"}
            for options, tasks, offline in (([], ["build"], False),
                                             (["--offline", "--", "compileJava", "test"], ["compileJava", "test"], True)):
                with self.subTest(offline=offline), patch.dict(os.environ, env), \
                        patch.object(sys, "argv", ["build_263.py", *options]), \
                        patch.object(build.subprocess, "call", return_value=37) as call:
                    self.assertEqual(37, build.main())
                    args, = call.call_args.args
                    expected = [str(build.ROOT / "mod/gradlew.bat"), *tasks]
                    if offline:
                        expected.append("--offline")
                    expected += ["--console=plain", "-Dorg.gradle.jvmargs=-Xmx3G",
                                 "-PnirncraftBuildDir=" + str(root / "build"),
                                 "--project-cache-dir", str(root / "project-cache")]
                    self.assertEqual(expected, args)
                    child = call.call_args.kwargs
                    self.assertEqual(build.ROOT / "mod", child["cwd"])
                    self.assertEqual(str(root / "java"), child["env"]["JAVA_HOME"])
                    self.assertEqual(str(root / "gradle-home"), child["env"]["GRADLE_USER_HOME"])
                    self.assertNotIn("DEBUG", child["env"])
                    for name, value in env.items():
                        self.assertEqual(value, os.environ[name])

    @unittest.skipUnless(POWERSHELL, "PowerShell is not installed")
    def test_powershell_build_wrapper_syntax(self):
        wrapper = str(Path(__file__).with_name("build_263.ps1")).replace("'", "''")
        script = ("$parseTokens=$null\n$parseErrors=$null\n"
                  "[System.Management.Automation.Language.Parser]::ParseFile('" + wrapper +
                  "',[ref]$parseTokens,[ref]$parseErrors) | Out-Null\n"
                  "if ($parseErrors.Count) { $parseErrors | Out-String | Write-Error; exit 1 }")
        result = subprocess.run([POWERSHELL, "-NoProfile", "-NonInteractive", "-Command", script],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    @unittest.skipUnless(POWERSHELL, "PowerShell is not installed")
    def test_powershell_delegate_forwards_arrays_offline_exit_code_and_parent_environment(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            wrappers = root / "wrapper with spaces"
            wrappers.mkdir()
            wrapper = wrappers / "build_263.ps1"
            wrapper.write_bytes(Path(__file__).with_name("build_263.ps1").read_bytes())
            # The launcher stub records arguments; it never executes the build helper.
            recorder = root / "launcher recorder.py"
            recorder.write_text("import json, os, sys\nfrom pathlib import Path\n"
                                "keys = ('JAVA_HOME', 'GRADLE_USER_HOME', 'DEBUG')\n"
                                "Path(os.environ['GUARD_TEST_RECORD']).write_text(json.dumps({"
                                "'args': sys.argv[1:], 'env': {k: os.environ.get(k) for k in keys}, 'cwd': os.getcwd()}))\n"
                                "sys.stderr.write('stub launcher stderr\\n')\n"
                                "sys.exit(int(os.environ['GUARD_TEST_EXIT']))\n", encoding="utf-8")

            def quote(value):
                return "'" + str(value).replace("'", "''") + "'"

            cases = (("", ["build"], False, 0),
                     ("-Tasks @('compileJava','test','-Psample=value with spaces') -Offline",
                      ["compileJava", "test", "-Psample=value with spaces"], True, 37),
                     ("-Tasks @('test') -Offline:$false", ["test"], False, 19))
            for invocation, tasks, offline, exit_code in cases:
                with self.subTest(invocation=invocation):
                    record, after = root / "forwarded.json", root / "after.json"
                    env = os.environ.copy()
                    parent = {"JAVA_HOME": "parent java", "GRADLE_USER_HOME": "parent gradle", "DEBUG": "parent debug"}
                    env.update(parent)
                    env.update({"GUARD_TEST_RECORD": str(record), "GUARD_TEST_EXIT": str(exit_code)})
                    script = ("$ErrorActionPreference='Stop'\nfunction py { & " + quote(sys.executable) + " -I " +
                              quote(recorder) + " @args }\n& " + quote(wrapper) + " " + invocation + "\n"
                              "$code=$LASTEXITCODE\n"
                              "[pscustomobject]@{JAVA_HOME=$env:JAVA_HOME; GRADLE_USER_HOME=$env:GRADLE_USER_HOME; DEBUG=$env:DEBUG} | "
                              "ConvertTo-Json -Compress | Set-Content -LiteralPath " + quote(after) + " -Encoding UTF8\n"
                              "exit $code")
                    result = subprocess.run([POWERSHELL, "-NoProfile", "-NonInteractive", "-Command", script],
                                            cwd=root, env=env, capture_output=True, text=True, timeout=10)
                    self.assertEqual(exit_code, result.returncode, result.stdout + result.stderr)
                    actual = json.loads(record.read_text())
                    expected = ["-3", "-I", str(wrappers / "build_263.py")]
                    if offline:
                        expected.append("--offline")
                    expected += ["--", *tasks]
                    self.assertEqual(expected, actual["args"])
                    self.assertEqual(parent, actual["env"])
                    self.assertEqual(root.resolve(), Path(actual["cwd"]).resolve())
                    self.assertEqual(parent, json.loads(after.read_text(encoding="utf-8-sig")))

    def test_imported_live_actions_lock_before_status_and_through_acknowledgement(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            game = root / "game"
            game.mkdir()
            batch = game / "nirncraft-live.txt"
            batch.write_text("previous batch")
            lock = root / ".nirncraft-live.lock"

            def status():
                self.assert_busy(lock, os.getpid())
                self.assertEqual("previous batch", batch.read_text())
                return {"client": {"fresh": True}}

            def acknowledge(path):
                self.assert_busy(lock, os.getpid())
                return {"controlVersion": batch.stat().st_mtime_ns // 1_000_000}

            with patch.object(live, "PRISM_ROOT", root), patch.object(live, "GAME", game), \
                    patch.object(live, "status", side_effect=status), patch.object(live, "read_json", side_effect=acknowledge):
                live.live_actions(["identity"])
            self.assertEqual("identity\n", batch.read_text())
            self.assertEqual(0, self.contender(lock).returncode)

    def test_live_contention_precedes_status_and_cancel_cannot_overwrite_batch(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            game = root / "game"
            game.mkdir()
            batch = game / "nirncraft-live.txt"
            batch.write_text("active batch")
            with self.holder(root / ".nirncraft-live.lock"), patch.object(live, "PRISM_ROOT", root), \
                    patch.object(live, "GAME", game), patch.object(live, "status") as status:
                for cancel in (False, True):
                    with self.assertRaises(GuardBusy):
                        live.live_actions(["stop"], cancel=cancel)
                status.assert_not_called()
                with self.assertRaises(GuardBusy):
                    live.submit(["list"])
            self.assertEqual("active batch", batch.read_text())

    def test_imported_submit_holds_guard_through_queue_write_and_completion(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            dev = root / "world-preview"
            lock = root / ".nirncraft-live.lock"
            real_atomic = live.atomic

            def ready():
                self.assert_busy(lock, os.getpid())

            def finish_job(path, data):
                self.assert_busy(lock, os.getpid())
                real_atomic(path, data)
                real_atomic(dev / "outbox" / (path.stem + ".done"), b"status=OK\n")

            with patch.object(live, "PRISM_ROOT", root), patch.object(live, "DEV", dev), \
                    patch.object(live, "require_ready", side_effect=ready), patch.object(live, "atomic", side_effect=finish_job), \
                    contextlib.redirect_stdout(io.StringIO()):
                job = live.submit(["list"])
            self.assertEqual("list\n", (dev / "inbox" / (job + ".cmd")).read_text())
            self.assertEqual(0, self.contender(lock).returncode)

    def test_live_busy_and_readiness_checks_still_refuse_writes_and_release_guard(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for client, message in (({"fresh": False}, "absent/stale"), ({"fresh": True, "queued": 1}, "queue is busy")):
                with patch.object(live, "PRISM_ROOT", root), patch.object(live, "GAME", root / "game"), \
                        patch.object(live, "status", return_value={"client": client}):
                    with self.assertRaisesRegex(RuntimeError, message):
                        live.live_actions(["identity"])
                self.assertFalse((root / "game/nirncraft-live.txt").exists())
                self.assertEqual(0, self.contender(root / ".nirncraft-live.lock").returncode)

    def test_live_cli_guards_mutations_but_read_only_dispatch_remains_available(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            lock = root / ".nirncraft-live.lock"
            with self.holder(lock), patch.object(live, "PRISM_ROOT", root), patch.object(live, "dispatch") as dispatch:
                for action in ("status", "storage", "profile"):
                    with patch.object(sys, "argv", ["live_world.py", action]):
                        live.main()
                self.assertEqual(3, dispatch.call_count)
                for action in ("launch", "close", "enable", "shader"):
                    with patch.object(sys, "argv", ["live_world.py", action]), self.assertRaises(GuardBusy):
                        live.main()
                self.assertEqual(3, dispatch.call_count)

    def test_cli_guard_and_imported_live_guard_can_nest(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            lock = root / ".nirncraft-live.lock"

            def dispatch(args):
                with live.live_guard():
                    self.assert_busy(lock, os.getpid())
                self.assert_busy(lock, os.getpid())

            with patch.object(live, "PRISM_ROOT", root), patch.object(live, "dispatch", side_effect=dispatch), \
                    patch.object(sys, "argv", ["live_world.py", "close"]):
                live.main()
            self.assertEqual(0, self.contender(lock).returncode)


if __name__ == "__main__":
    unittest.main()
