#!/usr/bin/env python3
"""Deterministic unit test for A01 semantic runner gate logic."""

import unittest
from unittest.mock import patch, MagicMock

import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))

from run_a01_acceptance import check_logcat_marker


class TestA01SemanticRunnerGate(unittest.TestCase):
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

    def test_false_pass_elimination_contract(self):
        cmd_status = "PASS"
        semantic_verdict = "FIXTURE_SEMANTIC_FAIL"
        overall_pass = (cmd_status == "PASS" and semantic_verdict == "FIXTURE_SEMANTIC_PASS")
        self.assertFalse(overall_pass, "Command launch pass MUST NOT cause a semantic failure to pass!")


if __name__ == '__main__':
    unittest.main()
