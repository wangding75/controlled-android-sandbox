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

from run_c4_r05_rd import PhaseFailure, artifact_index, read_summary, require_pass, safe_name  # noqa: E402


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


if __name__ == "__main__":
    unittest.main()
