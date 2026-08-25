#!/usr/bin/env python3
"""Unit and contract tests for the C4-R04 fail-closed acceptance predicates."""

from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "capability"))

from c4_r04_fail_closed import (  # noqa: E402
    INJECTION_EXPECTATIONS,
    _base_case,
    evaluate_case,
    injection_case,
    run_failure_injection_suite,
    run_recovery_contract_suite,
)


class C4R04FailClosedTests(unittest.TestCase):
    def test_valid_launch_requires_dynamic_evidence_and_passes(self) -> None:
        decision = evaluate_case(_base_case())
        self.assertEqual("PASS", decision["status"])
        self.assertFalse(decision["failureDetected"])

    def test_static_marker_cannot_replace_empty_window(self) -> None:
        case = _base_case()
        case["window"].update({"windowsCount": 0, "reportedDrawn": False, "hasVisible": False})
        case["operation"]["firstFrameDrawn"] = False
        decision = evaluate_case(case)
        self.assertEqual("FAIL", decision["status"])
        self.assertIn("WINDOWS_EMPTY", decision["failures"])

    def test_unclassified_retry_is_fail_closed(self) -> None:
        case = _base_case()
        case["retryDecision"] = "RETRY_UNKNOWN"
        decision = evaluate_case(case)
        self.assertEqual("FAIL", decision["status"])
        self.assertEqual("UNCLASSIFIED_RETRY_DECISION", decision["errorClassification"])

    def test_all_required_injections_are_failures(self) -> None:
        for name, expected in INJECTION_EXPECTATIONS.items():
            with self.subTest(name=name):
                decision = evaluate_case(injection_case(name))
                self.assertEqual("FAIL", decision["status"])
                self.assertEqual(expected, decision["errorClassification"])

    def test_injection_suite_preserves_first_failure_and_artifact_index(self) -> None:
        with tempfile.TemporaryDirectory(prefix="c4-r04-test-") as directory:
            report = run_failure_injection_suite(Path(directory))
            self.assertEqual("PASS", report["status"])
            index = json.loads((Path(directory) / "artifact-index.json").read_text(encoding="utf-8"))
            self.assertTrue(index["artifacts"])
            for row in report["scenarios"]:
                self.assertTrue(row["firstFailurePreserved"])
                self.assertTrue(row["guardPassed"])
                self.assertTrue((Path(directory) / row["scenario"] / "first-observation.json").is_file())
                self.assertTrue((Path(directory) / row["scenario"] / "final-observation.json").is_file())

    def test_recovery_is_separate_from_first_failure(self) -> None:
        with tempfile.TemporaryDirectory(prefix="c4-r04-recovery-") as directory:
            report = run_recovery_contract_suite(Path(directory))
            self.assertEqual("PASS", report["status"])
            first = json.loads((Path(directory) / "first-failure.json").read_text(encoding="utf-8"))
            recovery = json.loads((Path(directory) / "recovery-observation.json").read_text(encoding="utf-8"))
            self.assertNotEqual(first["requestId"], recovery["requestId"])
            self.assertFalse(report["automaticRetryPerformed"])

    def test_mutation_busy_is_safe_but_duplicate_success_is_not(self) -> None:
        safe = injection_case("duplicate-add")
        safe["secondOperationStatus"] = "BUSY"
        self.assertEqual("PASS", evaluate_case(safe)["status"])
        bad = copy.deepcopy(safe)
        bad["secondOperationStatus"] = "SUCCEEDED"
        decision = evaluate_case(bad)
        self.assertEqual("FAIL", decision["status"])
        self.assertEqual("DUPLICATE_MUTATION_ACCEPTED", decision["errorClassification"])


if __name__ == "__main__":
    unittest.main()
