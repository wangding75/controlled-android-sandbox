#!/usr/bin/env python3
"""Fail-closed validator for the C1 phase gate evidence."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
EXPECTED_FULL_REGRESSION_CASES = (
    "RD-09-framework-activity-result-transport",
    "RD-06-framework-transport-probe",
    "RD-11-framework-job-work-item-probe",
    "RD-10-framework-foreground-service-transport",
    "RD-07-process-death-generation-recovery",
    "RD-08-isolated-service-framework-transport",
    "RD-06-clear-delete-reinstall-transaction",
    "RD-08-cross-abi-process-death-generation-recovery",
    "RD-09-cross-abi-clear-delete-reinstall-transaction",
)


def load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def git_head() -> str:
    return subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, check=True, text=True,
        capture_output=True,
    ).stdout.strip()


def environment(payload: dict[str, Any]) -> dict[str, str]:
    raw = payload.get("environment") or payload.get("device") or {}
    if "environment" in raw and isinstance(raw["environment"], dict):
        raw = raw["environment"]
    return {
        "instance_name": str(raw.get("instance_name") or raw.get("instanceName") or ""),
        "serial": str(raw.get("adb_serial") or raw.get("serial") or ""),
        "model": str(raw.get("device_name") or raw.get("model") or ""),
        "api": str(raw.get("api_level") or raw.get("api") or ""),
        "abi": str(raw.get("abi") or raw.get("abi_list") or ""),
        "boot_id": str(raw.get("boot_id") or raw.get("bootId") or ""),
        "android_id": str(raw.get("android_id") or ""),
    }


def matrix_environment(payload: dict[str, Any]) -> dict[str, str]:
    matrix = payload.get("matrix")
    if isinstance(matrix, dict) and isinstance(matrix.get("environment"), dict):
        return environment({"environment": matrix["environment"]})
    return environment(payload)


def check(checks: list[dict[str, Any]], name: str, passed: bool, detail: str, evidence: list[str]) -> None:
    checks.append({"id": name, "status": "PASS" if passed else "FAIL", "detail": detail, "evidence": evidence})


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--receipt", type=Path,
        default=ROOT / "verification/catch-up/C1-GATE/c1-gate-receipt.json",
    )
    parser.add_argument(
        "--provider-receipt", type=Path,
        default=ROOT / "verification/catch-up/C1-GATE/c1-gate-provider-rd-summary.json",
    )
    parser.add_argument(
        "--full-regression-dir", type=Path,
        default=ROOT / "verification/catch-up/C1-GATE/full-regression",
    )
    args = parser.parse_args()
    checks: list[dict[str, Any]] = []
    receipt_paths = {
        "C1-T01": ROOT / "verification/catch-up/C1-T01/c1-t01-rd-summary.json",
        "C1-T02": ROOT / "verification/catch-up/C1-T02/c1-t02-rd-summary.json",
        "C1-T03": ROOT / "verification/catch-up/C1-T03/c1-t03-rd-summary.json",
        "C1-T04": ROOT / "verification/catch-up/C1-T04/c1-t04-rd-summary.json",
        "C1-T05": ROOT / "verification/catch-up/C1-T05/c1-t05-rd-summary.json",
        "C1-T06": ROOT / "verification/catch-up/C1-T06/c1-t06-rd-summary.json",
        "C1-T07": ROOT / "verification/catch-up/C1-T07/c1-t07-rerun-acceptance.json",
    }
    prior = {task: load(path) for task, path in receipt_paths.items()}
    provider = load(args.provider_receipt)
    current_head = git_head()
    current_env = environment(provider)

    check(
        checks, "current-head-provider-receipt",
        provider.get("overall_pass") is True
        and provider.get("loops_per_user_minimum") == 50
        and provider.get("virtual_users") == [0, 1]
        and provider.get("pressure_seconds_requested") == 1800
        and (provider.get("git") or {}).get("commit") == current_head,
        "current HEAD provider campaign is PASS with two users, 50 loops, and 1800 seconds",
        [str(args.provider_receipt.relative_to(ROOT))],
    )
    provider_result = provider.get("result") or {}
    check(
        checks, "provider-pressure-duration",
        provider_result.get("pass") is True
        and float(provider_result.get("elapsed_seconds", 0)) >= 1800,
        f"observed provider pressure elapsed={provider_result.get('elapsed_seconds')}",
        [str(args.provider_receipt.relative_to(ROOT))],
    )

    t1 = prior["C1-T01"]
    t1_matrix = t1.get("matrix") or {}
    check(
        checks, "c1-t01-dual-user-50-loop",
        t1_matrix.get("overall_pass") is True
        and t1_matrix.get("loops_per_user") == 50
        and t1_matrix.get("virtual_users") == [0, 1]
        and t1_matrix.get("passed_case_count") == t1_matrix.get("expected_case_count") == 700,
        "Activity/Application task matrix is 700/700 across both users",
        [str(receipt_paths["C1-T01"].relative_to(ROOT))],
    )
    t2 = prior["C1-T02"].get("matrix") or {}
    check(
        checks, "c1-t02-dual-user-pressure",
        t2.get("overall_pass") is True
        and t2.get("virtual_users") == [0, 1]
        and t2.get("loops_per_user_minimum") == 50
        and t2.get("pressure_seconds_requested") == 1800
        and float(t2.get("pressure_seconds_observed", 0)) >= 1800,
        "Service/FGS/Job evidence contains both users and the retained 30-minute run",
        [str(receipt_paths["C1-T02"].relative_to(ROOT))],
    )
    t3 = prior["C1-T03"]
    check(
        checks, "c1-t03-dual-user-50-loop",
        t3.get("overall_pass") is True
        and t3.get("loops_per_user") == 50
        and sorted(t3.get("virtual_users") or []) == [0, 1]
        and len(t3.get("results") or []) == 2
        and all(row.get("pass") is True for row in t3.get("results") or []),
        "Broadcast evidence is PASS for both users at 50 loops",
        [str(receipt_paths["C1-T03"].relative_to(ROOT))],
    )
    t4 = prior["C1-T04"]
    t4_users = t4.get("virtual_users") or []
    check(
        checks, "c1-t04-dual-user-baseline",
        t4.get("overall_pass") is True and sorted(t4_users) == [0, 1],
        "Provider baseline evidence is retained; current pressure is checked separately",
        [str(receipt_paths["C1-T04"].relative_to(ROOT)), str(args.provider_receipt.relative_to(ROOT))],
    )
    t5 = prior["C1-T05"]
    t5_users = t5.get("users") or {}
    check(
        checks, "c1-t05-dual-user",
        t5.get("overall_pass") is True and {str(key) for key in t5_users} == {"0", "1"},
        "PendingIntent/Alarm/Notification holder evidence is PASS for both users",
        [str(receipt_paths["C1-T05"].relative_to(ROOT))],
    )
    t6 = prior["C1-T06"]
    t6_steps = t6.get("steps") or []
    def lifecycle_step_passed(step: dict[str, Any]) -> bool:
        result = step.get("result") or {}
        if result.get("returncode") == 0:
            return True
        installs = result.get("installs")
        if isinstance(installs, list) and installs:
            return all(item.get("returncode") == 0 for item in installs if isinstance(item, dict))
        # The downgrade-policy transition is an intentional negative assertion: the package
        # service must reject v1 after v2 has been installed, and the harness records that
        # expected rejection as a non-zero command with a FAIL result payload.
        if step.get("name") == "lifecycle_downgrade_policy":
            rejected = result.get("result") or {}
            return (
                result.get("status") == "FAIL"
                and "Package downgrade rejected" in str(rejected.get("errorMessage", ""))
            )
        return False
    check(
        checks, "c1-t06-package-lifecycle",
        t6.get("status") == "PASS"
        and len(t6_steps) == 29
        and all(lifecycle_step_passed(step) for step in t6_steps),
        "Package lifecycle receipt has 29 passing transition steps",
        [str(receipt_paths["C1-T06"].relative_to(ROOT))],
    )
    t7 = prior["C1-T07"]
    t7_death = t7.get("death_recovery") or {}
    t7_support = t7.get("supporting_device_cases") or {}
    check(
        checks, "c1-t07-process-abi-recovery",
        t7.get("task_acceptance_status") == "PASS"
        and t7_death.get("iterations") == 50
        and t7_death.get("pass") == 50
        and t7_death.get("fail") == 0
        and all(value == "PASS" for value in t7_support.values()),
        "Process/ABI/recovery receipt has 50/50 loops and supporting teardown cases",
        [str(receipt_paths["C1-T07"].relative_to(ROOT))],
    )

    transcript = args.full_regression_dir.parent / "c1-gate-run.txt"
    transcript_text = transcript.read_text(encoding="utf-8", errors="replace") if transcript.exists() else ""
    full_evidence: list[str] = [str(args.full_regression_dir.relative_to(ROOT)), str(transcript.relative_to(ROOT))]
    full_pass = bool(transcript_text) and "RESULT: PASS suite=t57-rd-full-regression" in transcript_text
    missing_cases: list[str] = []
    for case in EXPECTED_FULL_REGRESSION_CASES:
        device_path = args.full_regression_dir / f"{case}-device.json"
        if not device_path.is_file():
            missing_cases.append(case)
            continue
        device = load(device_path)
        full_pass = full_pass and device.get("gitHead") == current_head
        normalized = environment({"device": device})
        for key in ("serial", "model", "api", "boot_id"):
            full_pass = full_pass and normalized.get(key) == current_env.get(key)
        if f"RESULT: PASS case={case}" not in transcript_text:
            missing_cases.append(case)
    check(
        checks, "current-head-full-regression",
        full_pass and not missing_cases and "RESULT: BLOCKED" not in transcript_text,
        "all nine current-HEAD RD regression cases passed on the same resolved device snapshot; "
        f"missing_or_mismatched={missing_cases}",
        full_evidence,
    )

    convergence_files = (
        args.full_regression_dir / "RD-06-clear-delete-reinstall-transaction-result.json",
        args.full_regression_dir / "RD-09-cross-abi-clear-delete-reinstall-transaction-result.json",
        args.full_regression_dir / "RD-07-process-death-generation-recovery-result.json",
        args.full_regression_dir / "RD-08-cross-abi-process-death-generation-recovery-result.json",
    )
    convergence_ok = True
    convergence_detail: list[str] = []
    for path in convergence_files:
        if not path.is_file():
            convergence_ok = False
            convergence_detail.append(f"missing:{path.name}")
            continue
        payload = load(path)
        if payload.get("status") != "PASS":
            convergence_ok = False
            convergence_detail.append(f"status:{path.name}={payload.get('status')}")
        if "afterClear" in payload and payload.get("afterClear") not in ("", None, []):
            convergence_ok = False
            convergence_detail.append(f"afterClear:{path.name}")
        if "companionFilesAfterClear" in payload and payload.get("companionFilesAfterClear") not in ("", None, []):
            convergence_ok = False
            convergence_detail.append(f"companionAfterClear:{path.name}")
    check(
        checks, "resource-convergence",
        convergence_ok,
        "clear/delete/restart/death and cross-ABI result files report PASS with no residual resources; "
        f"details={convergence_detail}",
        [str(path.relative_to(ROOT)) for path in convergence_files],
    )

    check(
        checks, "device-snapshot-consistency",
        current_env.get("instance_name") == "RD测试"
        and current_env.get("serial")
        and current_env.get("api") == "32"
        and current_env.get("boot_id"),
        f"instance={current_env.get('instance_name')} serial={current_env.get('serial')} "
        f"api={current_env.get('api')} boot_id={current_env.get('boot_id')}",
        [str(args.provider_receipt.relative_to(ROOT)), "verification/catch-up/C1-GATE/C1-GATE-final-device.json"],
    )

    passed = all(row["status"] == "PASS" for row in checks)
    result = {
        "schema_version": 1,
        "task": "C1-GATE",
        "status": "PASS" if passed else "BLOCKED",
        "maturity_level": "RD_BASELINE",
        "va_pro_equivalent": "NOT_PROVEN",
        "tested_git_head": current_head,
        "environment": current_env,
        "classification": "TEST_EVIDENCE_GAP",
        "known_issues": "No new runtime issue; the phase-level evidence gap is closed by this receipt." if passed else "Phase gate remains blocked until failed checks are repaired.",
        "checks": checks,
        "next_task": "C2-T01" if passed else "C1-GATE recovery",
    }
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"receipt": str(args.receipt), "status": result["status"], "failed_checks": [row["id"] for row in checks if row["status"] != "PASS"]}, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
