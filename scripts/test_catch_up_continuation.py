#!/usr/bin/env python3
from __future__ import annotations

import sys
import unittest
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
_MODULE_PATH = ROOT / "scripts/verify-catch-up-continuation.py"
_SPEC = importlib.util.spec_from_file_location("verify_catch_up_continuation", _MODULE_PATH)
assert _SPEC and _SPEC.loader
continuation = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(continuation)


class ContinuationLedgerTests(unittest.TestCase):
    def test_parse_rows_accepts_revalidation_task_ids(self) -> None:
        rows = continuation.parse_rows(
            "| C4-R01 | evidence correction | PENDING | C4-T05 | - | - |\n"
            "| C5-T01 | skipped scope | NOT_APPLICABLE | C2,C3 | - | receipt |\n"
        )
        self.assertEqual(rows["C4-R01"]["status"], "PENDING")
        self.assertEqual(rows["C4-R01"]["dependencies"], "C4-T05")
        self.assertEqual(rows["C5-T01"]["status"], "NOT_APPLICABLE")

    def test_expands_range_dependencies(self) -> None:
        self.assertEqual(
            continuation.expand_dependencies("C1-T01..T03,C2-T01"),
            ["C1-T01", "C1-T02", "C1-T03", "C2-T01"],
        )

    def test_first_ready_pending_task(self) -> None:
        rows = {
            "C0-T01": {"status": "DONE", "dependencies": "BOOTSTRAP-DOCS"},
            "C0-T02": {"status": "PENDING", "dependencies": "C0-T01"},
            "C0-T03": {"status": "PENDING", "dependencies": "C0-T02"},
        }
        self.assertEqual(continuation.find_ready_task(rows, "BOOTSTRAP-DOCS"), "C0-T02")

    def test_latest_appended_receipt_wins(self) -> None:
        text = (
            "### C0-T02：historical attempt\n"
            "- **状态**：BLOCKED\n\n"
            "### C0-T02 final recovery：completed attempt\n"
            "- **状态**：DONE\n\n"
            "## next section\n"
        )
        section = continuation.receipt_section(text, "C0-T02")
        self.assertIn("DONE", section)
        self.assertNotIn("BLOCKED", section)

    def test_fixed_issue_status_is_governed_elsewhere(self) -> None:
        from tools.capability import campaign_status

        self.assertIn("FIXED", campaign_status.ISSUE_STATUS)

    def test_serial_scan_allows_only_non_operational_guards(self) -> None:
        scan = continuation.scan_executable_serials()
        self.assertEqual(scan["unexpected"], [])


if __name__ == "__main__":
    unittest.main()
