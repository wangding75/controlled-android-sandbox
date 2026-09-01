#!/usr/bin/env python3
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools/capability"))

from run_c4_r03_low_memory_continuation import child_command  # noqa: E402
from run_c4_r03_rd import run_post_restart_rebootstrap  # noqa: E402


class C4R03PostRestartRebootstrapTests(unittest.TestCase):
    def test_child_flag_is_explicit_and_only_added_to_resume(self) -> None:
        args = SimpleNamespace(
            instance_name="RD测试",
            loops=25,
            users="0,1",
            targets="fixture,dingtalk,quark,hongguo,fanqie",
        )
        resume = {
            "target": "dingtalk",
            "user": 1,
            "iteration": 19,
            "mode": "hot",
            "previousLane": str(ROOT / "previous-lane"),
        }
        with tempfile.TemporaryDirectory() as temporary:
            child = Path(temporary) / "attempt-003"
            ordinary = child_command(args, child, resume=resume, attempt=3)
            recovery = child_command(
                args, child, resume=resume, attempt=3,
                post_restart_rebootstrap=True,
            )
        self.assertNotIn("--post-restart-rebootstrap", ordinary)
        self.assertIn("--post-restart-rebootstrap", recovery)

    def test_rebootstrap_accepts_only_prepared_terminal(self) -> None:
        target = {"target": "dingtalk", "package": "com.example.dingtalk"}
        resume = {"kind": "MANUAL_RESUME_AFTER_RESTART", "resumeMode": "hot"}
        response = {
            "status": "PASS",
            "result": {
                "operationId": "rebootstrap-operation",
                "operation": {"status": "PREPARED"},
            },
        }
        with tempfile.TemporaryDirectory() as temporary:
            with patch("run_c4_r03_rd.debug_command", return_value=response) as command:
                record = run_post_restart_rebootstrap(
                    "resolved-device-from-environment", Path(temporary), target, 1, 3, resume)
            payload = Path(temporary) / "post-restart-rebootstrap" / "dingtalk" / "user-1" / \
                "post-restart-rebootstrap.json"
            self.assertTrue(payload.is_file())
        self.assertEqual(record["status"], "PASS")
        self.assertEqual(record["operationStatus"], "PREPARED")
        command_args = command.call_args.args[1]
        self.assertIn("prepare", command_args)
        self.assertIn("--es", command_args)
        self.assertEqual(record["retryBudget"], 0)
        self.assertFalse(record["automaticRetryPerformed"])

    def test_rebootstrap_does_not_promote_structured_failure(self) -> None:
        target = {"target": "dingtalk", "package": "com.example.dingtalk"}
        response = {
            "status": "FAIL",
            "result": {
                "operationId": "rebootstrap-operation",
                "operation": {"status": "FAILED"},
            },
        }
        with tempfile.TemporaryDirectory() as temporary:
            with patch("run_c4_r03_rd.debug_command", return_value=response):
                record = run_post_restart_rebootstrap(
                    "resolved-device-from-environment", Path(temporary), target, 1, 3, {})
        self.assertEqual(record["status"], "FAIL")
        self.assertEqual(record["operationStatus"], "FAILED")


if __name__ == "__main__":
    unittest.main()
