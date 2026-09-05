"""Self-tests for the unified verification harness.

These are host-side tests of the contract, parser, retry and report machinery;
the RD smoke entry point separately exercises the same code against a real
device.
"""

from __future__ import annotations

import struct
import sys
import tempfile
import time
import unittest
import zlib
from pathlib import Path

if __package__ in {None, ""}:
    _ROOT = Path(__file__).resolve().parents[2]
    if str(_ROOT) not in sys.path:
        sys.path.insert(0, str(_ROOT))
    from tools.verification.capabilities.smoke import _matches_request, _session, _timeout_classification
    from tools.verification.core.assertions import classify_failure_text
    from tools.verification.core.models import (
        AttemptResult,
        ContractError,
        FailureClass,
        ResultState,
        Testcase,
        TestcaseSpec,
    )
    from tools.verification.core.policy import HarnessTimeout, RetryPolicy, TimeoutKind, TimeoutPolicy, run_bounded
    from tools.verification.core.runner import AttemptExecution, run_case
    from tools.verification.device.screen import inspect_png
    from tools.verification.memory_limiter import (
        MEMORY_LIMITER_CLASSIFICATION,
        PRODUCT_CRASH,
        classify_exit,
        classify_recovery,
    )
    from tools.verification.matrix_validator import MatrixAccountingError, validate_cells, validate_report
    from tools.verification.reporting.summary import build_summary, render_compact_report
    from tools.verification.run_api33_capabilities import _merge_case_logcat
else:
    from .capabilities.smoke import _matches_request, _session, _timeout_classification
    from .core.assertions import classify_failure_text
    from .core.models import (
        AttemptResult,
        ContractError,
        FailureClass,
        ResultState,
        Testcase,
        TestcaseSpec,
    )
    from .core.policy import HarnessTimeout, RetryPolicy, TimeoutKind, TimeoutPolicy, run_bounded
    from .core.runner import AttemptExecution, run_case
    from .device.screen import inspect_png
    from .memory_limiter import (
        MEMORY_LIMITER_CLASSIFICATION,
        PRODUCT_CRASH,
        classify_exit,
        classify_recovery,
    )
    from .matrix_validator import MatrixAccountingError, validate_cells, validate_report
    from .reporting.summary import build_summary, render_compact_report
    from .run_api33_capabilities import _merge_case_logcat


def _chunk(kind: bytes, body: bytes) -> bytes:
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)


def _write_png(path: Path, *, value: int, width: int = 128, height: int = 128) -> None:
    raw = b"".join(b"\x00" + bytes([value, value, value]) * width for _ in range(height))
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", header)
        + _chunk(b"IDAT", zlib.compress(raw))
        + _chunk(b"IEND", b"")
    )


class HarnessContractTests(unittest.TestCase):
    def test_result_enum_and_fail_closed_retry(self) -> None:
        spec = TestcaseSpec("T-RETRY", "parser", "retry contract")
        with tempfile.TemporaryDirectory() as temp:
            calls = {"count": 0}

            def executor(_context: object, _path: Path, _attempt: int) -> AttemptExecution:
                calls["count"] += 1
                if calls["count"] == 1:
                    return AttemptExecution(
                        ResultState.FAIL,
                        actual={"logcat": "BLACK_SCREEN"},
                        failure_signature="BLACK_SCREEN",
                    )
                return AttemptExecution(ResultState.PASS, actual={"retry": True})

            case = run_case(
                spec,
                context=type("Context", (), {"root": Path(temp)})(),
                output_dir=Path(temp),
                device={"api_level": 32, "abi": "x86_64", "page_size": 4096},
                retry_policy=RetryPolicy(),
                executor=executor,
            )
            self.assertEqual(case.result, ResultState.FAIL)
            self.assertEqual(case.failure_class, FailureClass.PRODUCT_DEFECT)
            self.assertEqual(case.retry_attempt.result, ResultState.PASS)
            self.assertEqual(calls["count"], 2)
            payload = case.to_dict()
            self.assertEqual(payload["result"], "FAIL")
            self.assertEqual(payload["final_classification"], "PRODUCT_DEFECT")

        with self.assertRaises(ContractError):
            validate = {
                "testcase_id": "illegal",
                "capability": "x",
                "description": "x",
                "device": {},
                "api_level": 32,
                "abi": "x86_64",
                "page_size": 4096,
                "virtual_user": 0,
                "guest_package": "",
                "package_revision": "",
                "precondition": "",
                "operation": "",
                "expected": {},
                "actual": {},
                "result": "SUCCESS_WITH_WARNING",
                "failure_class": None,
                "duration_ms": 0,
                "artifacts": [],
                "first_attempt": None,
                "retry_attempt": None,
                "final_classification": None,
            }
            if __package__ in {None, ""}:
                from tools.verification.core.models import validate_testcase_payload
            else:
                from .core.models import validate_testcase_payload

            validate_testcase_payload(validate)

    def test_timeout_policy_has_distinct_bounded_operations(self) -> None:
        policy = TimeoutPolicy(
            install=0.11,
            add_import=0.12,
            cold_launch=0.13,
            warm_launch=0.14,
            first_frame=0.15,
            process_death=0.16,
            recovery=0.17,
        )
        self.assertEqual(policy.seconds(TimeoutKind.INSTALL), 0.11)
        self.assertEqual(policy.seconds(TimeoutKind.FIRST_FRAME), 0.15)
        with self.assertRaises(HarnessTimeout):
            run_bounded(lambda: time.sleep(0.2), TimeoutKind.FIRST_FRAME, policy)

    def test_failure_classification_is_diagnosis_only(self) -> None:
        self.assertEqual(classify_failure_text("LAUNCH_FIRST_FRAME_MISSING"), FailureClass.PRODUCT_DEFECT)
        self.assertEqual(classify_failure_text("ADB_COMMAND_TIMEOUT"), FailureClass.ENVIRONMENT)
        self.assertEqual(classify_failure_text("CONTRACTERROR parser"), FailureClass.HARNESS_DEFECT)
        self.assertEqual(
            _timeout_classification("FATAL EXCEPTION: main\nProcess: guest.pkg", "guest.pkg"),
            FailureClass.PRODUCT_DEFECT,
        )
        self.assertEqual(
            _timeout_classification(
                "FATAL EXCEPTION: main\nProcess: com.warden.controlledsandbox.debug",
                "guest.pkg",
            ),
            FailureClass.PRODUCT_DEFECT,
        )
        self.assertEqual(
            _timeout_classification("no result yet", "guest.pkg"),
            FailureClass.ENVIRONMENT,
        )
        self.assertEqual(_session({"operation": {"sessionId": "s1"}}), "s1")

    def test_retry_can_strengthen_final_failure_class_without_changing_fail(self) -> None:
        spec = TestcaseSpec("T-CLASSIFY", "process", "diagnostic classification")
        with tempfile.TemporaryDirectory() as temp:
            calls = {"count": 0}

            def executor(_context: object, _path: Path, _attempt: int) -> AttemptExecution:
                calls["count"] += 1
                if calls["count"] == 1:
                    return AttemptExecution(
                        ResultState.FAIL,
                        actual={"timeout": True},
                        failure_class=FailureClass.ENVIRONMENT,
                        failure_signature="DEBUG_RESULT_TIMEOUT",
                    )
                return AttemptExecution(
                    ResultState.FAIL,
                    actual={"crash": True},
                    failure_class=FailureClass.PRODUCT_DEFECT,
                    failure_signature="FATAL_EXCEPTION",
                )

            case = run_case(
                spec,
                context=type("Context", (), {"root": Path(temp)})(),
                output_dir=Path(temp),
                device={"api_level": 32, "abi": "x86_64", "page_size": 4096},
                retry_policy=RetryPolicy(),
                executor=executor,
            )
            self.assertEqual(case.result, ResultState.FAIL)
            self.assertEqual(case.first_attempt.failure_class, FailureClass.ENVIRONMENT)
            self.assertEqual(case.failure_class, FailureClass.PRODUCT_DEFECT)

    def test_png_parser_rejects_black_and_accepts_content(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            black = root / "black.png"
            white = root / "white.png"
            _write_png(black, value=0)
            _write_png(white, value=255)
            self.assertFalse(inspect_png(black)["non_black"])
            self.assertTrue(inspect_png(white)["non_black"])

    def test_command_parser_and_compact_report_do_not_inline_logs(self) -> None:
        self.assertTrue(_matches_request(
            {"command": "launch", "package": "guest", "requestId": "r1"},
            "launch", "guest", "r1",
        ))
        self.assertFalse(_matches_request(
            {"command": "launch", "package": "guest", "requestId": "old"},
            "launch", "guest", "r1",
        ))
        spec = TestcaseSpec("S01", "package", "one")
        case = Testcase(spec, {"api_level": 32}, 32, "x86_64", 4096)
        case.add_attempt(AttemptResult(1, ResultState.PASS, actual={"logcat": "SECRET_RAW_LOG"}))
        run = {
            "start_head": "a" * 40,
            "final_head": "b" * 40,
            "branch": "feature/test",
            "device_metadata": {"api_level": 32, "abi": "x86_64", "page_size": 4096},
            "testcases": [case.to_dict()],
            "evidence_root": "out/verification/test",
            "harness_tests": "PASS",
        }
        run["summary"] = build_summary(run, run["testcases"])
        report = render_compact_report(run)
        self.assertIn("S01", report)
        self.assertNotIn("SECRET_RAW_LOG", report)
        self.assertNotIn("SUCCESS_WITH_WARNING", report)

    def test_capability_scan_keeps_post_marker_crash_evidence(self) -> None:
        logcat = _merge_case_logcat(
            "FRAMEWORK_PROBE_PASS",
            "FATAL EXCEPTION: main\nProcess: com.warden.controlledsandbox.fixture",
        )
        self.assertIn("FRAMEWORK_PROBE_PASS", logcat)
        self.assertIn("FATAL EXCEPTION", logcat)

    def test_memory_limiter_exit_is_not_confused_with_an_ordinary_crash(self) -> None:
        memory_exit = (
            "reason=13 (REASON_OTHER)\n"
            "description=MemoryLimiter:AnonSwap process constrained"
        )
        ordinary_crash = "reason=4 (CRASH)\ndescription=java.lang.IllegalStateException"
        self.assertEqual(classify_exit(memory_exit), MEMORY_LIMITER_CLASSIFICATION)
        self.assertEqual(classify_exit(ordinary_crash), PRODUCT_CRASH)
        self.assertNotEqual(classify_exit(memory_exit), PRODUCT_CRASH)
        self.assertEqual(
            classify_recovery(process_died=True, cleanup_ok=True, restarted=True),
            "EXPECTED_PLATFORM_BEHAVIOR",
        )
        self.assertEqual(
            classify_recovery(process_died=True, cleanup_ok=False, restarted=True),
            "PRODUCT_DEFECT",
        )

    def test_matrix_validator_closes_the_eight_previous_accounting_gaps(self) -> None:
        report = (
            Path(__file__).resolve().parents[2]
            / "reports"
            / "t57-r03"
            / "c6"
            / "C6_T01G_CROSS_API_CLOSURE_REPORT.md"
        )
        summaries = validate_report(report)
        unified = summaries["unified"]
        version_specific = summaries["version_specific"]
        self.assertEqual(unified.total, 48)
        self.assertEqual(unified.count("PASS"), 35)
        self.assertEqual(unified.count("NOT_IN_CURRENT_SCOPE"), 6)
        self.assertEqual(unified.count("DEFERRED_ENVIRONMENT"), 7)
        self.assertEqual(version_specific.total, 31)
        self.assertEqual(version_specific.count("PASS"), 27)
        self.assertEqual(version_specific.count("NOT_IN_CURRENT_SCOPE"), 2)
        self.assertEqual(version_specific.count("DEFERRED_ENVIRONMENT"), 2)

    def test_matrix_validator_rejects_duplicate_and_missing_cells(self) -> None:
        with self.assertRaisesRegex(MatrixAccountingError, "duplicate id"):
            validate_cells(
                "duplicate",
                [
                    {"id": "same", "status": "PASS"},
                    {"id": "same", "status": "PASS"},
                ],
            )
        with self.assertRaisesRegex(MatrixAccountingError, "no status"):
            validate_cells("missing", [{"id": "cell"}])

    def test_matrix_validator_rejects_unknown_status_and_bad_total(self) -> None:
        with self.assertRaisesRegex(MatrixAccountingError, "unknown status"):
            validate_cells("unknown", [{"id": "cell", "status": "SOFT_PASS"}])
        with self.assertRaisesRegex(MatrixAccountingError, "total mismatch"):
            validate_cells(
                "total",
                [{"id": "cell", "status": "PASS"}],
                expected_total=2,
            )

    def test_matrix_validator_requires_reasons_for_deferred_cells(self) -> None:
        with self.assertRaisesRegex(MatrixAccountingError, "deferred without reason"):
            validate_cells(
                "deferred",
                [{"id": "cell", "status": "DEFERRED_ENVIRONMENT"}],
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
