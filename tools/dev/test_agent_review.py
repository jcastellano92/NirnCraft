"""Portable reviewer protocol regressions; no network, game or model calls."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import agent_job
import agent_review
import agent_watch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "harness"))
import review


class IndependentReviewTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.scratch = Path(self.temp.name)
        self.env = patch.dict(os.environ, {"NIRNCRAFT_AGENT_SCRATCH": str(self.scratch),
                                           "NIRNCRAFT_REVIEW_PROVIDER": "codex"})
        self.env.start()
        self.addCleanup(self.env.stop)
        os.environ.pop("NIRNCRAFT_REVIEW_MODEL", None)

    def test_codex_reads_attached_evidence_and_returns_final_answer_only(self):
        image = self.scratch / "before after.png"
        image.write_bytes(b"image fixture")

        def run(cmd, **kw):
            self.assertEqual(kw["input"], "judge the attached before/after")
            self.assertEqual(cmd[cmd.index("--sandbox") + 1], "read-only")
            self.assertIn("--ignore-user-config", cmd)
            self.assertIn("project_doc_max_bytes=0", cmd)
            self.assertEqual(cmd[cmd.index("--image") + 1], str(image.resolve()))
            self.assertEqual(cmd[cmd.index("--model") + 1], "gpt-6.1-terra")
            self.assertNotEqual(Path(kw["cwd"]), agent_review.ROOT)
            Path(cmd[cmd.index("--output-last-message") + 1]).write_text("OK 1 tests look right", encoding="utf-8")
            return subprocess.CompletedProcess(cmd, 0, "events are not a verdict", "")

        with patch.object(agent_review.shutil, "which", return_value="codex.exe"), \
                patch.object(agent_review.subprocess, "run", side_effect=run):
            self.assertEqual(agent_review.ask("judge the attached before/after", images=[image]), "OK 1 tests look right")

    def test_explicit_model_is_passed_unchanged(self):
        def run(cmd, **kw):
            self.assertEqual(cmd[cmd.index("--model") + 1], "chosen-by-owner")
            Path(cmd[cmd.index("--output-last-message") + 1]).write_text("answer", encoding="utf-8")
            return subprocess.CompletedProcess(cmd, 0, "", "")
        with patch.object(agent_review.shutil, "which", return_value="codex.exe"), \
                patch.object(agent_review.subprocess, "run", side_effect=run):
            self.assertEqual(agent_review.ask("claim", model="chosen-by-owner"), "answer")

    def test_failed_empty_and_missing_evidence_never_become_clean_reviews(self):
        with patch.object(agent_review.shutil, "which", return_value="codex.exe"), \
                patch.object(agent_review.subprocess, "run") as run:
            with self.assertRaises(FileNotFoundError):
                agent_review.ask("claim", images=[self.scratch / "missing.png"])
            run.assert_not_called()
            run.return_value = subprocess.CompletedProcess([], 1, "", "rate limited")
            with self.assertRaisesRegex(RuntimeError, "rate limited"):
                agent_review.ask("claim")
            run.return_value = subprocess.CompletedProcess([], 0, "no final answer file", "")
            with self.assertRaisesRegex(RuntimeError, "no final answer"):
                agent_review.ask("claim")

    def test_claude_error_envelope_is_not_a_verdict(self):
        os.environ["NIRNCRAFT_REVIEW_PROVIDER"] = "claude"
        with patch.object(agent_review.shutil, "which", return_value="claude.exe"), \
                patch.object(agent_review.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, json.dumps({"is_error": True, "result": "rate limit"}), "")
            with self.assertRaisesRegex(RuntimeError, "rate limit"):
                agent_review.ask("claim")
            run.return_value = subprocess.CompletedProcess([], 0, json.dumps({"result": "OK 2 tests look right"}), "")
            self.assertEqual(agent_review.ask("claim"), "OK 2 tests look right")
            self.assertEqual(run.call_args.args[0][3], "haiku")

    def test_auto_uses_installed_provider_without_retrying_a_failed_one(self):
        os.environ["NIRNCRAFT_REVIEW_PROVIDER"] = "auto"
        with patch.object(agent_review.shutil, "which", side_effect=lambda p: p if p == "claude" else None):
            self.assertEqual(agent_review.identity(), "claude:haiku")
        with patch.object(agent_review.shutil, "which", return_value="installed"):
            self.assertEqual(agent_review.identity(), "codex:gpt-6.1-terra")

    def test_queue_wrapper_preserves_arguments_and_existing_judge_hook(self):
        scheduler = self.scratch / "heavy.py"
        scheduler.write_text("", encoding="utf-8")
        args = ["--exclusive", "--loop", "middle", "--why", "check one frame", "worker: game", "--", "py", "a.py"]
        cmd, env = agent_job.command(args, {"NIRNCRAFT_HEAVY": str(scheduler)})
        self.assertEqual(cmd, [sys.executable, str(scheduler), *args])
        self.assertEqual(json.loads(env["HEAVY_GATE_CLAUDE"])[-1], "--legacy-judge")
        _, env = agent_job.command([], {"NIRNCRAFT_HEAVY": str(scheduler), "HEAVY_GATE_CLAUDE": "existing"})
        self.assertEqual(env["HEAVY_GATE_CLAUDE"], "existing")
        with self.assertRaisesRegex(FileNotFoundError, "do not bypass"):
            agent_job.command([], {"NIRNCRAFT_HEAVY": str(self.scratch / "absent")})

    def test_event_watcher_waits_for_complete_rows_and_handles_rotation(self):
        log = self.scratch / "events.jsonl"
        log.write_bytes(b'{"event":"old"}\n')
        tail = agent_watch.Tail(log)
        self.assertEqual(tail.read(), [])
        with log.open("ab") as stream:
            stream.write(b'{"event":"run_finished"}')
        self.assertEqual(tail.read(), [])
        with log.open("ab") as stream:
            stream.write(b'\n')
        self.assertEqual(tail.read(), [{"event": "run_finished"}])
        log.write_bytes(b'{"rc":1}\n')
        self.assertEqual(tail.read(), [{"rc": 1}])
        self.assertIsNone(agent_watch.summary("harness:main", {"event": "irrelevant"}))
        self.assertIn("run_finished", agent_watch.summary("harness:main", {"event": "run_finished", "counts": {"fail": 1}}))

    def test_page_batches_cover_index_without_reviewing_stale_pages(self):
        folder = self.scratch / "agent"
        folder.mkdir()
        (folder / "index.txt").write_text("\n".join(f"page-{i:02d} row 1: test-{i} [shaders]" for i in range(1, 10)))
        for i in range(1, 11):
            (folder / f"page-{i:02d}.png").write_bytes(b"fixture")
        with patch.object(review, "_ask", side_effect=["OK 8 tests look right", "OK 1 tests look right"]) as ask, \
                patch.object(review, "_log"):
            self.assertEqual(review.pages(folder), [])
            self.assertEqual(review.LAST_PAGES["ok"], 9)
            self.assertEqual([len(call.args[3]) for call in ask.call_args_list], [8, 1])
            seen = [p.name for call in ask.call_args_list for p in call.args[3]]
            self.assertNotIn("page-10.png", seen)
            self.assertIn("test-9", ask.call_args_list[1].args[0])

    def test_ambiguous_or_malformed_claims_are_unverified(self):
        item = {"key": "x", "title": "test", "claim": "fixed", "images": [str(self.scratch / "x.png")]}
        answers = ['[1]', '[{"key":"x","verdict":"pass"}]',
                   '[{"key":"x","verdict":"pass","reason":"yes"},{"key":"x","verdict":"fail","reason":"no"}]']
        with patch.object(review, "_log"):
            for answer in answers:
                with patch.object(review, "_ask", return_value=answer):
                    self.assertEqual(review.claims([item])["x"]["verdict"], "unsure")

    def test_missing_or_empty_page_evidence_is_not_a_clean_review(self):
        with self.assertRaises(FileNotFoundError):
            review.pages(self.scratch)
        (self.scratch / "index.txt").write_text("\n")
        with self.assertRaisesRegex(ValueError, "empty"):
            review.pages(self.scratch)
        self.assertEqual(review.LAST_PAGES, {})


if __name__ == "__main__":
    unittest.main()
