#!/usr/bin/env python3
"""Record C4-T01 SX freeze against a live RD snapshot."""

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

TASK_ID = "C4-T01"
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
VERIFICATION = ROOT / "verification/catch-up/C4-T01"
STATIC_GATES = (
    "scripts/check-c4-t01-sx-freeze.py",
    "scripts/check-c3-t06-art-xposed-decision.py",
)


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
    output = artifacts_dir("catch-up-c4-t01")
    VERIFICATION.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    errors: list[str] = []
    static_rows: list[dict[str, Any]] = []
    environment: dict[str, Any] = {}
    freeze: dict[str, Any] = {}
    try:
        static_rows = run_static_gates()
        write_json(output / "static-gates.json", static_rows)
        failed = [row["gate"] for row in static_rows if row["returncode"] != 0]
        if failed:
            raise RuntimeError(f"static gates failed: {failed}")
        environment = resolve_rd_environment(args.instance)
        write_json(output / "environment.json", environment)
        freeze = json.loads((VERIFICATION / "c4-t01-freeze.json").read_text(encoding="utf-8"))
    except CampaignBlocked as exc:
        errors.append(str(exc))
        write_json(output / "blocked.json", {"code": exc.code, "detail": exc.detail})
    except Exception as exc:
        errors.append(str(exc))

    evidence = {
        "schema_version": 1,
        "campaign_id": TASK_ID,
        "capability": "package_lifecycle_clear_delete_reinstall",
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
            {"id": "C4-T01-ACCEPTANCE", "classification": "FAIL", "summary": error}
            for error in errors
        ],
        "known_issues": ["KI-R03-046 C4-T01 SX freeze inventory"],
        "evidence_files": [],
        "maturity_level": "RD_BASELINE",
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
        "notes": (
            "C4-T01 freezes SX Gradle/engine/UI/data/hook entries onto CAS mappings. "
            "It does not migrate the engine, delete BlackBox, or claim SX/DingTalk PASS. "
            f"engine_methods={len(freeze.get('engine_methods') or [])} "
            f"blockers={[item.get('id') for item in freeze.get('blockers') or []]}"
        ),
    }
    schema_errors = validate_evidence(evidence)
    errors.extend(schema_errors)
    evidence["failures"].extend(
        {"id": "C4-T01-EVIDENCE-SCHEMA", "classification": "FAIL", "summary": error}
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
        str(VERIFICATION / "c4-t01-freeze.json"),
        str(VERIFICATION / "c4-t01-rd-summary.json"),
        str(VERIFICATION / "c4-t01-local-verification.json"),
        str(ROOT / "docs/review/C4_T01_SX_DEPENDENCY_FREEZE_DESIGN.md"),
    ]
    write_json(evidence_path, evidence)
    write_json(VERIFICATION / "c4-t01-rd-summary.json", evidence)
    local = {
        "task_id": TASK_ID,
        "status": "PASS" if not errors else "FAIL",
        "environment": environment,
        "static_gates": static_rows,
        "freeze_counts": {
            "gradle_modules": len(freeze.get("gradle_modules") or []),
            "engine_methods": len(freeze.get("engine_methods") or []),
            "ui_entries": len(freeze.get("ui_entries") or []),
            "f1_f5_hooks": len(freeze.get("f1_f5_hooks") or []),
            "data_stores": len(freeze.get("data_stores") or []),
            "dynamic_loads": len(freeze.get("dynamic_loads") or []),
        },
        "migration_order": freeze.get("migration_order"),
        "errors": errors,
        "non_claims": [
            "not SX business PASS",
            "not DingTalk PASS",
            "not BlackBox removed from the live SX tree",
            "not VA Pro equivalence",
        ],
    }
    write_json(VERIFICATION / "c4-t01-local-verification.json", local)
    report_path.write_text(
        "\n".join(
            [
                "# C4-T01 SX dependency freeze",
                "",
                f"- status: {evidence['rd_result']}",
                f"- instance: {evidence['instance_name']}",
                f"- device: {evidence['device_name']} API {evidence['api_level']}",
                f"- engine methods: {local['freeze_counts']['engine_methods']}",
                "- VA Pro equivalent: NOT_PROVEN",
                "",
            ]
        ),
        encoding="utf-8",
    )
    print(f"{'PASS' if not errors else 'FAIL'} {TASK_ID} rd_result={evidence['rd_result']}")
    for error in errors:
        print(f" - {error}")
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
