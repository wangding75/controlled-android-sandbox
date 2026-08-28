#!/usr/bin/env python3
"""Pure tests for the C4-R05 orchestration classification helpers."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools/capability"))

from run_c4_r05_rd import (  # noqa: E402
    PhaseFailure,
    artifact_index,
    launch_continuation,
    read_summary,
    require_pass,
    safe_name,
)


class C4R05OrchestratorTests(unittest.TestCase):
    def test_safe_name_is_deterministic(self) -> None:
        self.assertEqual(safe_name("round 1/first-frame"), "round_1_first-frame")

    def test_require_pass_accepts_only_zero_and_pass(self) -> None:
        record = {"label": "ok", "returncode": 0, "summary": {"status": "PASS"}}
        self.assertIs(require_pass(record, "phase"), record)

    def test_require_pass_preserves_first_failure(self) -> None:
        record = {"label": "bad", "returncode": 1,
                  "summary": {"status": "FAIL", "blockedAt": {"iteration": 1}}}
        with self.assertRaises(PhaseFailure) as context:
            require_pass(record, "launch")
        self.assertEqual(context.exception.phase, "launch")
        self.assertEqual(context.exception.evidence, record)

    def test_read_summary_and_artifact_index_are_machine_readable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            summary = root / "summary.json"
            summary.write_text(json.dumps({"status": "PASS"}), encoding="utf-8")
            self.assertEqual(read_summary(summary)["status"], "PASS")
            rows = artifact_index(root)
            self.assertEqual(rows[0]["path"], "summary.json")
            self.assertEqual(len(rows[0]["sha256"]), 64)

    def test_environment_restart_row_is_resumable_without_erasing_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            lane = Path(temporary) / "launch-matrix"
            attempt_one = lane / "attempt-001" / "attempts" / "dingtalk" / "user-0" / "hot-006"
            attempt_one.mkdir(parents=True)
            failed = {
                "task": "C4-R03", "target": "dingtalk", "user": 0,
                "iteration": 1, "mode": "cold", "failureDetected": True,
                "commandResult": {
                    "status": "ERROR",
                    "detail": "RD_ENVIRONMENT_RESOLUTION_BLOCKED: debug-command-result timeout",
                },
                "device": {
                    "surfaceNonEmpty": False,
                    "screenshot": {"error": "UnidentifiedImageError: offline"},
                },
            }
            (attempt_one / "case.json").write_text(json.dumps(failed), encoding="utf-8")
            continuation = launch_continuation(lane, "dingtalk", "0", 1)
            self.assertEqual(continuation["target"], "dingtalk")
            self.assertEqual(continuation["iteration"], 1)
            self.assertEqual(continuation["mode"], "cold")
            self.assertTrue(continuation["environmentInterruption"]["originalFailurePreserved"])

    def test_environment_restart_row_can_be_replaced_by_later_observation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            lane = Path(temporary) / "launch-matrix"
            failed_dir = lane / "attempt-001" / "attempts" / "dingtalk" / "user-0" / "cold-001"
            passed_dir = lane / "attempt-002" / "attempts" / "dingtalk" / "user-0" / "cold-001"
            hot_dir = lane / "attempt-001" / "attempts" / "dingtalk" / "user-0" / "hot-001"
            failed_dir.mkdir(parents=True)
            passed_dir.mkdir(parents=True)
            hot_dir.mkdir(parents=True)
            failed = {
                "task": "C4-R03", "target": "dingtalk", "user": 0,
                "iteration": 1, "mode": "cold", "failureDetected": True,
                "commandResult": {
                    "status": "ERROR",
                    "detail": "RD_ENVIRONMENT_RESOLUTION_BLOCKED: debug-command-result timeout",
                },
                "device": {
                    "surfaceNonEmpty": False,
                    "screenshot": {"error": "UnidentifiedImageError: offline"},
                },
            }
            passed = {
                "task": "C4-R03", "target": "dingtalk", "user": 0,
                "iteration": 1, "mode": "cold", "failureDetected": False,
            }
            (failed_dir / "case.json").write_text(json.dumps(failed), encoding="utf-8")
            (passed_dir / "case.json").write_text(json.dumps(passed), encoding="utf-8")
            (hot_dir / "case.json").write_text(json.dumps({
                "task": "C4-R03", "target": "dingtalk", "user": 0,
                "iteration": 1, "mode": "hot", "failureDetected": False,
            }), encoding="utf-8")
            continuation = launch_continuation(lane, "dingtalk", "0", 1)
            self.assertTrue(continuation["complete"])
            self.assertEqual(continuation["recoveredEnvironmentCoordinates"],
                             [["dingtalk", 0, 1, "cold"]])


if __name__ == "__main__":
    unittest.main()
