#!/usr/bin/env python3
"""Deterministic unit tests for A01 acceptance runner gate logic."""

import unittest
from unittest.mock import patch, MagicMock

import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))

from run_a01_acceptance import (
    REQUIRED_GATES,
    check_logcat_marker,
    evaluate_gates,
)


def all_passing_tests():
    return {gate: {"pass": True} for gate in REQUIRED_GATES}


class TestCheckLogcatMarker(unittest.TestCase):
    @patch('run_a01_acceptance.run_adb')
    def test_pass_marker_detected(self, mock_run_adb):
        mock_run_adb.return_value = MagicMock(stdout="08-19 12:00:00.000  1000  1000 I CS_FIXTURE: FRAMEWORK_PROBE_TASK_REUSE_PASS action=android.intent.action.VIEW\n")
        res = check_logcat_marker("mock_serial", "FRAMEWORK_PROBE_TASK_REUSE_PASS", "FRAMEWORK_PROBE_TASK_REUSE_FAIL", wait_sec=0.0)
        self.assertEqual(res["verdict"], "FIXTURE_SEMANTIC_PASS")
        self.assertTrue(res["pass_marker_found"])
        self.assertFalse(res["fail_marker_found"])

    @patch('run_a01_acceptance.run_adb')
    def test_fail_marker_detected(self, mock_run_adb):
        mock_run_adb.return_value = MagicMock(stdout="08-19 12:00:00.000  1000  1000 E CS_FIXTURE: FRAMEWORK_PROBE_TASK_REUSE_FAIL reason=SECOND_ON_CREATE\n")
        res = check_logcat_marker("mock_serial", "FRAMEWORK_PROBE_TASK_REUSE_PASS", "FRAMEWORK_PROBE_TASK_REUSE_FAIL", wait_sec=0.0)
        self.assertEqual(res["verdict"], "FIXTURE_SEMANTIC_FAIL")
        self.assertFalse(res["pass_marker_found"])
        self.assertTrue(res["fail_marker_found"])

    @patch('run_a01_acceptance.run_adb')
    def test_fail_takes_precedence_over_pass(self, mock_run_adb):
        mock_run_adb.return_value = MagicMock(stdout="08-19 12:00:00.000 I CS_FIXTURE: FRAMEWORK_PROBE_TASK_REUSE_PASS\n08-19 12:00:01.000 E CS_FIXTURE: FRAMEWORK_PROBE_TASK_REUSE_FAIL\n")
        res = check_logcat_marker("mock_serial", "FRAMEWORK_PROBE_TASK_REUSE_PASS", "FRAMEWORK_PROBE_TASK_REUSE_FAIL", wait_sec=0.0)
        self.assertEqual(res["verdict"], "FIXTURE_SEMANTIC_FAIL")

    @patch('run_a01_acceptance.run_adb')
    def test_timeout_when_no_marker(self, mock_run_adb):
        mock_run_adb.return_value = MagicMock(stdout="08-19 12:00:00.000 I CS_FIXTURE: Some other unrelated log\n")
        res = check_logcat_marker("mock_serial", "FRAMEWORK_PROBE_TASK_REUSE_PASS", "FRAMEWORK_PROBE_TASK_REUSE_FAIL", wait_sec=0.0)
        self.assertEqual(res["verdict"], "FIXTURE_SEMANTIC_TIMEOUT")


class TestRequiredGateAggregation(unittest.TestCase):
    def test_all_required_gates_pass(self):
        tests = all_passing_tests()
        overall, failed = evaluate_gates(tests)
        self.assertTrue(overall)
        self.assertEqual(failed, [])

    def test_missing_gate_fails_closed(self):
        # A missing (unrecorded) gate must fail closed, not be treated as pass.
        tests = all_passing_tests()
        tests.pop("scale")
        overall, failed = evaluate_gates(tests)
        self.assertFalse(overall)
        self.assertIn("scale", failed)

    def test_single_scale_fail_fails_runner(self):
        tests = all_passing_tests()
        tests["scale"] = {"results": {"ScaleActivity095": {"status": "FAIL"}}, "pass": False}
        overall, failed = evaluate_gates(tests)
        self.assertFalse(overall)
        self.assertIn("scale", failed)

    def test_semantic_fail_fails_runner(self):
        tests = all_passing_tests()
        for gate in ("standard", "single_top", "single_task", "clear_top", "reorder_to_front"):
            tests[gate] = {"pass": False}
        overall, failed = evaluate_gates(tests)
        self.assertFalse(overall)
        self.assertEqual(set(failed), {"standard", "single_top", "single_task", "clear_top", "reorder_to_front"})

    def test_neighbor_fail_fails_runner(self):
        tests = all_passing_tests()
        tests["service"] = {"pass": False}
        tests["provider"] = {"pass": False}
        tests["pending_intent"] = {"pass": False}
        overall, failed = evaluate_gates(tests)
        self.assertFalse(overall)
        self.assertEqual(set(failed), {"service", "provider", "pending_intent"})

    def test_session_fencing_fail_fails_runner(self):
        tests = all_passing_tests()
        tests["session_fencing"] = {"pass": False}
        overall, failed = evaluate_gates(tests)
        self.assertFalse(overall)
        self.assertIn("session_fencing", failed)

    def test_all_required_gates_are_declared(self):
        self.assertEqual(
            set(REQUIRED_GATES),
            {"scale", "basic_launch", "activity_result", "standard", "single_top",
             "single_top_non_top", "single_task", "clear_top_standard", "clear_top",
             "reorder_to_front", "process_death", "session_fencing", "service", "provider",
             "pending_intent"},
        )


if __name__ == '__main__':
    unittest.main()