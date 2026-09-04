#!/usr/bin/env python3
"""Targeted tests for T57-R03 capability campaign infrastructure."""

from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "scripts"))

from campaign_status import REQUIRED_CAPABILITY_IDS  # noqa: E402
from common import validate_evidence  # noqa: E402
from run_rd_campaign import FORBIDDEN_SERIALS  # noqa: E402
import validate_campaign_infra  # noqa: E402


class CampaignInfraTests(unittest.TestCase):
    def test_registries_validate(self) -> None:
        self.assertEqual(validate_campaign_infra.validate_registry(), [])
        self.assertEqual(validate_campaign_infra.validate_issues(), [])
        self.assertEqual(validate_campaign_infra.validate_corpus(), [])
        self.assertEqual(validate_campaign_infra.validate_gates_and_schema(), [])

    def test_fourteen_capabilities(self) -> None:
        self.assertEqual(len(REQUIRED_CAPABILITY_IDS), 14)

    def test_no_hardcoded_rd_serials_in_new_tools(self) -> None:
        for path in HERE.glob("*.py"):
            if path.name.startswith("_"):
                continue
            text = path.read_text(encoding="utf-8")
            for serial in FORBIDDEN_SERIALS:
                if serial in text:
                    self.assertIn("FORBIDDEN_SERIALS", text)
                    self.assertNotIn(f'"{serial}"', text.split("FORBIDDEN_SERIALS", 1)[0])

    def test_rd_baseline_cannot_claim_va_pro_pass(self) -> None:
        payload = {
            "campaign_id": "T57-R03-01",
            "capability": "activity_framework",
            "branch": "feature/t57-r03-va-pro-capability-campaign",
            "commit": "deadbeef",
            "tree": "cafebabe",
            "timestamp": "2026-08-17T00:00:00+00:00",
            "host_os": "Windows",
            "android_environment": "MuMu RD测试",
            "device_name": "sample",
            "adb_serial": "dynamic",
            "api_level": "32",
            "abi": "x86",
            "build_result": "PASS",
            "static_result": "PASS",
            "targeted_result": "PASS",
            "rd_result": "PASS",
            "regression_result": "UNVERIFIED",
            "failures": [],
            "known_issues": [],
            "evidence_files": [],
            "maturity_level": "RD_BASELINE",
            "va_pro_equivalent": "PASS",
        }
        errors = validate_evidence(payload)
        self.assertTrue(any("va_pro_equivalent=PASS" in item for item in errors))

    def test_validator_cli(self) -> None:
        completed = subprocess.run(
            [sys.executable, str(HERE / "validate_campaign_infra.py")],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)

    def test_schema_is_json(self) -> None:
        schema = json.loads((HERE / "evidence_schema.json").read_text(encoding="utf-8"))
        self.assertIn("campaign_id", schema["required"])
        self.assertIn("maturity_level", schema["required"])

    def test_native_campaign_alias_selects_native_gates(self) -> None:
        from run_local_capability_audit import select_gates

        selected = select_gates("native", False)
        ids = [gate["id"] for gate in selected]
        self.assertIn("native-boundary", ids)
        self.assertIn("native-hostile-profile", ids)
        self.assertIn("native-abi-companion", ids)
        self.assertTrue(all("native_loader_jni_io" in (gate.get("capabilities") or []) for gate in selected))

    def test_native_enforcement_alias(self) -> None:
        from run_local_capability_audit import select_gates

        selected = select_gates("native-enforcement", False)
        ids = [gate["id"] for gate in selected]
        self.assertIn("native-boundary", ids)
        self.assertIn("native-hostile-profile", ids)
        self.assertIn("native-abi-companion", ids)

    def test_audit_requires_declared_issue_evidence(self) -> None:
        from run_local_capability_audit import classify_gate

        gate = {"known_issue_ids": ["KI-EXAMPLE"]}
        issues = [{
            "issue_id": "KI-EXAMPLE",
            "status": "RECORDED",
            "match_patterns": ["expected failure signature"],
        }]
        classification, matched = classify_gate(gate, "different failure signature", 1, issues)
        self.assertEqual(classification, "NEW_REGRESSION")
        self.assertEqual(matched, [])

    def test_audit_does_not_resurrect_fixed_issue(self) -> None:
        from run_local_capability_audit import classify_gate

        gate = {"known_issue_ids": ["KI-EXAMPLE"]}
        issues = [{
            "issue_id": "KI-EXAMPLE",
            "status": "FIXED",
            "match_patterns": ["old failure signature"],
        }]
        classification, matched = classify_gate(gate, "old failure signature", 1, issues)
        self.assertEqual(classification, "NEW_REGRESSION")
        self.assertEqual(matched, [])

    def test_audit_matches_structured_issue_evidence(self) -> None:
        from run_local_capability_audit import classify_gate

        gate = {"known_issue_ids": ["KI-STRUCTURED"]}
        issues = [{
            "issue_id": "KI-STRUCTURED",
            "status": "RECORDED",
            "match_patterns": [{"Permission Denial": "com.example.SYSTEM_HOLDER"}],
        }]
        classification, matched = classify_gate(
            gate,
            "W/ActivityManager: Permission Denial: com.example.SYSTEM_HOLDER",
            1,
            issues,
        )
        self.assertEqual(classification, "KNOWN_ISSUE")
        self.assertEqual(matched, ["KI-STRUCTURED"])


if __name__ == "__main__":
    unittest.main()
