#!/usr/bin/env python3
"""Record the C3-T06 ART/Xposed decision against a live RD snapshot."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import (  # noqa: E402
    ROOT,
    artifacts_dir,
    git_identity,
    host_os,
    now_iso,
    validate_evidence,
    write_json,
)
from run_rd_campaign import CampaignBlocked, resolve_rd_environment  # noqa: E402

TASK_ID = "C3-T06"
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
VERIFICATION = ROOT / "verification/catch-up/C3-T06"
STATIC_GATES = ("scripts/check-c3-t06-art-xposed-decision.py",)


def run_static_gates() -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for relative in STATIC_GATES:
        completed = subprocess.run(
            [sys.executable, str(ROOT / relative)],
            cwd=ROOT,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        )
        rows.append(
            {
                "gate": relative,
                "returncode": completed.returncode,
                "stdout": completed.stdout,
                "stderr": completed.stderr,
            }
        )
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", "--instance-name", dest="instance", default="RD测试")
    args = parser.parse_args()
    output = artifacts_dir("catch-up-c3-t06")
    VERIFICATION.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    errors: list[str] = []
    static_rows: list[dict[str, Any]] = []
    environment: dict[str, Any] = {}
    try:
        static_rows = run_static_gates()
        write_json(output / "static-gates.json", static_rows)
        failed = [row["gate"] for row in static_rows if row["returncode"] != 0]
        if failed:
            raise RuntimeError(f"static gates failed: {failed}")
        environment = resolve_rd_environment(args.instance)
        write_json(output / "environment.json", environment)
    except CampaignBlocked as exc:
        errors.append(str(exc))
        write_json(output / "blocked.json", {"code": exc.code, "detail": exc.detail})
    except Exception as exc:
        errors.append(str(exc))

    decision = "NOT_APPLICABLE"
    evidence = {
        "schema_version": 1,
        "campaign_id": TASK_ID,
        "capability": "native_loader_jni_io",
        "branch": identity.get("branch") or "unknown",
        "commit": identity.get("commit") or "unknown",
        "tree": identity.get("tree") or "unknown",
        "timestamp": now_iso(),
        "host_os": host_os(),
        "android_environment": json.dumps(environment, ensure_ascii=False, sort_keys=True),
        "device_name": str(environment.get("device_name") or ""),
        "adb_serial": str(environment.get("adb_serial") or ""),
        "api_level": environment.get("api_level"),
        "abi": str(environment.get("abi") or ""),
        "boot_id": str(environment.get("boot_id") or ""),
        "android_id": str(environment.get("android_id") or ""),
        "instance_name": str(environment.get("instance_name") or args.instance),
        "build_result": "NOT_APPLICABLE",
        "static_result": "PASS" if static_rows and all(row["returncode"] == 0 for row in static_rows) else "FAIL",
        "targeted_result": "PASS" if not errors else "FAIL",
        "rd_result": "PASS" if not errors else "FAIL",
        "regression_result": "NOT_APPLICABLE",
        "failures": [
            {"id": "C3-T06-ACCEPTANCE", "classification": "FAIL", "summary": error}
            for error in errors
        ],
        "known_issues": [
            "C3-T06 ART/Xposed module host is out of product scope",
        ],
        "evidence_files": [],
        "maturity_level": "RD_BASELINE",
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
        "notes": (
            f"decision={decision}; F1-F5 continue via C2 adapters; "
            "no production Xposed/Pine engine; C5-T04 inherits this ADR"
        ),
    }
    schema_errors = validate_evidence(evidence)
    errors.extend(schema_errors)
    evidence["failures"].extend(
        {"id": "C3-T06-EVIDENCE-SCHEMA", "classification": "FAIL", "summary": error}
        for error in schema_errors
    )
    evidence["rd_result"] = "PASS" if not errors else "FAIL"
    evidence["targeted_result"] = evidence["rd_result"]
    evidence_path = output / "evidence.json"
    report_path = output / "report.md"
    evidence["evidence_files"] = [
        str(output / "environment.json"),
        str(output / "static-gates.json"),
        str(evidence_path),
        str(report_path),
        str(VERIFICATION / "c3-t06-rd-summary.json"),
        str(VERIFICATION / "c3-t06-local-verification.json"),
        str(ROOT / "docs/review/C3_T06_ART_XPOSED_EXTENSION_ADR.md"),
    ]
    write_json(evidence_path, evidence)
    write_json(VERIFICATION / "c3-t06-rd-summary.json", evidence)
    local = {
        "task_id": TASK_ID,
        "status": "NOT_APPLICABLE" if not errors else "FAIL",
        "decision": decision,
        "product_question": "F2-F5 adapters only; arbitrary Xposed modules not in scope",
        "environment": environment,
        "static_gates": static_rows,
        "errors": errors,
        "non_claims": [
            "this is not VA Pro Xposed-changelog equivalence",
            "spoofer_project module hosting remains a later explicit SKU",
        ],
    }
    write_json(VERIFICATION / "c3-t06-local-verification.json", local)
    report_path.write_text(
        "\n".join(
            [
                "# C3-T06 ART/Xposed Compatibility Extension decision",
                "",
                f"- decision: {decision}",
                f"- instance: {evidence['instance_name']}",
                f"- device: {evidence['device_name']} API {evidence['api_level']}",
                "- VA Pro equivalent: NOT_PROVEN",
                "",
            ]
        ),
        encoding="utf-8",
    )
    print(
        f"{'PASS' if not errors else 'FAIL'} {TASK_ID} decision={decision} "
        f"rd_result={evidence['rd_result']}"
    )
    for error in errors:
        print(f" - {error}")
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
