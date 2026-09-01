#!/usr/bin/env python3
"""Pure tests for the C4-R05 orchestration classification helpers."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools/capability"))

from run_c4_r05_rd import (  # noqa: E402
    PhaseFailure,
    artifact_index,
    launch_continuation,
    read_summary,
    require_pass,
    run_launch_matrix,
    run_command,
    safe_name,
    DEFAULT_PHASE_TIMEOUT_SECONDS,
)
from run_c4_r03_low_memory_continuation import (  # noqa: E402
    classify_low_memory,
    reconstruct_interrupted_lane,
    seed_existing_lane,
)
from types import SimpleNamespace


class C4R05OrchestratorTests(unittest.TestCase):
    def test_r05_phase_timeout_default_is_twelve_hours(self) -> None:
        self.assertEqual(DEFAULT_PHASE_TIMEOUT_SECONDS, 12 * 60 * 60)

    def test_launch_matrix_passes_phase_timeout_to_child_and_host_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch("run_c4_r05_rd.run_command") as mocked:
                mocked.return_value = {
                    "label": "launch",
                    "returncode": 0,
                    "timedOut": False,
                    "summary": {"status": "PASS"},
                }
                run_launch_matrix(
                    "RD测试", 25, "0,1", "fixture", root / "round", root,
                )
            command = mocked.call_args.args[1]
            self.assertIn("--child-timeout-seconds", command)
            child_index = command.index("--child-timeout-seconds")
            self.assertEqual(command[child_index + 1], str(12 * 60 * 60))
            self.assertEqual(mocked.call_args.kwargs["timeout_seconds"], 12 * 60 * 60)

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

    def test_timeout_records_process_tree_termination_before_continuation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            record = run_command(
                "bounded-timeout",
                [sys.executable, "-c", "import time; time.sleep(5)"],
                Path(temporary),
                timeout_seconds=1,
            )
        self.assertTrue(record["timedOut"])
        self.assertEqual(record["returncode"], 124)
        self.assertEqual(record["processTermination"]["pid"], record["processPid"])
        self.assertTrue(record["processTermination"]["attempted"])

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

    def test_full_lane_seed_preserves_low_memory_failure_and_recovery_rows(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            lane = root / "launch-matrix"
            failed_dir = lane / "attempt-001" / "attempts" / "dingtalk" / "user-0" / "cold-001"
            recovered_dir = lane / "attempt-002" / "attempts" / "dingtalk" / "user-0" / "cold-001"
            hot_dir = lane / "attempt-002" / "attempts" / "dingtalk" / "user-0" / "hot-001"
            failed_dir.mkdir(parents=True)
            recovered_dir.mkdir(parents=True)
            hot_dir.mkdir(parents=True)
            exit_info = failed_dir / "first-failure-full" / "application-exit-info.txt"
            exit_info.parent.mkdir(parents=True)
            exit_info.write_text(
                "package: com.warden.controlledsandbox.debug\n"
                "process=com.warden.controlledsandbox.debug reason=3 (LOW_MEMORY)\n",
                encoding="utf-8",
            )
            failed = {
                "task": "C4-R03", "target": "dingtalk", "user": 0,
                "iteration": 1, "mode": "cold", "failureDetected": True,
                "startedAt": "2026-09-01T00:00:00Z", "completedAt": "2026-09-01T00:00:01Z",
                "artifacts": str(failed_dir),
                "commandResult": {
                    "status": "ERROR",
                    "detail": "RD_ENVIRONMENT_RESOLUTION_BLOCKED: debug-command-result timeout",
                },
            }
            recovered = {
                "task": "C4-R03", "target": "dingtalk", "user": 0,
                "iteration": 1, "mode": "cold", "failureDetected": False,
                "startedAt": "2026-09-01T00:00:02Z", "completedAt": "2026-09-01T00:00:03Z",
            }
            hot = {
                "task": "C4-R03", "target": "dingtalk", "user": 0,
                "iteration": 1, "mode": "hot", "failureDetected": False,
                "startedAt": "2026-09-01T00:00:04Z", "completedAt": "2026-09-01T00:00:05Z",
            }
            (failed_dir / "case.json").write_text(json.dumps(failed), encoding="utf-8")
            (recovered_dir / "case.json").write_text(json.dumps(recovered), encoding="utf-8")
            (hot_dir / "case.json").write_text(json.dumps(hot), encoding="utf-8")
            args = SimpleNamespace(
                instance_name="RD测试", loops=1, users="0", targets="dingtalk",
                output=root / "seed-output",
            )
            summary = reconstruct_interrupted_lane(args, lane)
            low_memory_classification = classify_low_memory(summary)
            seeded = seed_existing_lane(args, lane)
            continuation = launch_continuation(lane, "dingtalk", "0", 1)

        self.assertEqual(len(summary["rows"]), 3)
        self.assertEqual(summary["blockedAt"]["target"], "dingtalk")
        self.assertEqual(len(seeded["sourceAttempts"]), 2)
        self.assertEqual(len(seeded["seededLowMemoryEvents"]), 1)
        self.assertEqual(len((seeded["summary"] or {}).get("rows", [])), 3)
        self.assertEqual(low_memory_classification["classification"], "LOW_MEMORY")
        self.assertTrue(continuation["complete"])
        self.assertEqual(continuation["recoveredEnvironmentCoordinates"],
                         [["dingtalk", 0, 1, "cold"]])


if __name__ == "__main__":
    unittest.main()
