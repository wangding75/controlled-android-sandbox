#!/usr/bin/env python3
"""C1-T01 RD_BASELINE Activity/Task matrix runner.

The runner is deliberately RD-only.  It resolves MuMu ``RD测试`` at runtime, runs the
package-neutral task fixtures for both virtual users, and fails closed unless the fixture
observation, CAS runtime route/mapping, physical task dump, and real Back transition agree.
Raw evidence is written below artifacts/capability-audit/catch-up-c1-t01/; a compact receipt is
written to the tracked verification/catch-up/C1-T01 directory.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ROOT, artifacts_dir, git_identity, host_os, now_iso, write_json
from run_a01_acceptance import (
    capture_activity_evidence,
    evaluate_task_system_evidence,
    parse_task_semantic_evidence,
    safe_debug_command,
    wait_for_task_event,
)
from run_rd_campaign import (
    DEBUG_ACTIVITY,
    GUEST_PACKAGE,
    HOST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    read_debug_command_result,
    resolve_rd_environment,
    run_adb,
)

CAMPAIGN_ID = "CAS-C1-T01-RD"
DEFAULT_INSTANCE = "RD" + chr(0x6D4B) + chr(0x8BD5)
DEFAULT_LOOPS = 50
VIRTUAL_USERS = (0, 1)
TRUST = ["--ez", "trustNativeGuest", "true"]

TASK_CASES = (
    ("standard", "com.warden.controlledsandbox.fixture.StandardTaskProbeActivity", "standard", 8.0),
    ("single_top_top", "com.warden.controlledsandbox.fixture.SingleTopProbeActivity", "single_top_top", 8.0),
    ("single_top_non_top", "com.warden.controlledsandbox.fixture.SingleTopNonTopProbeActivity", "single_top_non_top", 8.0),
    ("single_task", "com.warden.controlledsandbox.fixture.TaskSemanticsProbeActivity", "single_task", 10.0),
    ("clear_top_standard", "com.warden.controlledsandbox.fixture.ClearTopStandardProbeActivity", "clear_top_standard", 10.0),
    ("clear_top_single_top", "com.warden.controlledsandbox.fixture.ClearTopProbeActivity", "clear_top_single_top", 10.0),
    ("reorder_to_front", "com.warden.controlledsandbox.fixture.ReorderToFrontProbeActivity", "reorder_to_front", 10.0),
)


def _safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value)


def _json_command(
    serial: str,
    command: str,
    user: int,
    component: str = "",
    *,
    force_stop_host: bool = False,
) -> dict[str, Any]:
    extras = [
        "--es", "command", command,
        "--es", "package", GUEST_PACKAGE,
        "--ei", "user", str(user),
        *TRUST,
    ]
    if component:
        extras += ["--es", "component", component]
    return safe_debug_command(
        serial, extras, deadline_sec=90, force_stop_host=force_stop_host
    )


def _reset_case(serial: str) -> None:
    for package in (HOST_PACKAGE, GUEST_PACKAGE):
        run_adb(serial, ["shell", "am", "force-stop", package], check=False)
    # The physical Host task and Guest process must settle before a fresh route is issued.
    time.sleep(0.3)
    run_adb(serial, ["logcat", "-c"], check=False)


def _prepare_user(serial: str, user: int) -> dict[str, Any]:
    result = _json_command(serial, "import-prepare", user, force_stop_host=True)
    operation = (result.get("result") or {}).get("operation") or {}
    return {
        "status": result.get("status"),
        "operation": operation,
        "raw": result,
        "pass": result.get("status") == "PASS",
    }


def _case_once(
    serial: str,
    raw_dir: Path,
    user: int,
    loop: int,
    gate: str,
    component: str,
    evidence_case: str,
    wait_seconds: float,
) -> dict[str, Any]:
    case_dir = raw_dir / f"user-{user}" / f"loop-{loop:03d}" / _safe_name(gate)
    case_dir.mkdir(parents=True, exist_ok=True)
    _reset_case(serial)
    before_path = capture_activity_evidence(serial, case_dir, gate, "before")
    launch = _json_command(serial, "launch-component", user, component)
    request_log = wait_for_task_event(serial, evidence_case, "BACK_REQUEST", wait_seconds)
    transition_path = capture_activity_evidence(
        serial, case_dir, gate, "transition", launch, request_log
    )
    transition_log = wait_for_task_event(serial, evidence_case, "BACK_COMPLETE", 4.0)
    time.sleep(0.1)
    after_path = capture_activity_evidence(serial, case_dir, gate, "after")
    before = json.loads(before_path.read_text(encoding="utf-8"))
    transition = json.loads(transition_path.read_text(encoding="utf-8"))
    after = json.loads(after_path.read_text(encoding="utf-8"))
    fixture = parse_task_semantic_evidence(transition_log, evidence_case)
    semantic = evaluate_task_system_evidence(
        evidence_case,
        component,
        fixture,
        before,
        {**transition, "lifecycle_logcat": transition_log},
        after,
    )
    task_pass = (
        launch.get("status") == "PASS"
        and semantic["semantic_assertions"].get("fixture_lifecycle_pass") is True
        and semantic["semantic_assertions"].get("system_task_pass") is True
        and semantic["semantic_assertions"].get("token_mapping_pass") is True
        and semantic["semantic_assertions"].get("back_stack_pass") is True
    )
    full_log_path = case_dir / "logcat-full.txt"
    full_log_path.write_text(
        run_adb(serial, ["logcat", "-d", "-v", "threadtime"], check=False).stdout or "",
        encoding="utf-8",
        errors="replace",
    )
    result = {
        "user": user,
        "loop": loop,
        "gate": gate,
        "component": component,
        "launch_status": launch.get("status"),
        "semantic_verdict": semantic.get("verdict"),
        "semantic": semantic,
        "raw_evidence": {
            "before": str(before_path),
            "transition": str(transition_path),
            "after": str(after_path),
            "logcat": str(full_log_path),
        },
        "pass": task_pass,
    }
    write_json(case_dir / "result.json", result)
    return result


def _run_case_with_retry(
    serial: str,
    raw_dir: Path,
    user: int,
    loop: int,
    case: tuple[str, str, str, float],
) -> dict[str, Any]:
    last: dict[str, Any] = {}
    for attempt in (1, 2):
        try:
            last = _case_once(serial, raw_dir, user, loop, *case)
            last["attempt"] = attempt
            if last.get("pass"):
                return last
        except Exception as error:  # preserve the failure and retry after a clean boundary
            last = {
                "user": user,
                "loop": loop,
                "gate": case[0],
                "component": case[1],
                "attempt": attempt,
                "pass": False,
                "classification": "HARNESS_OR_ENVIRONMENT_FAILURE",
                "error": f"{error.__class__.__name__}: {error}",
            }
        if attempt == 1:
            _reset_case(serial)
    return last


def run_matrix(serial: str, environment: dict[str, Any], raw_dir: Path, loops: int) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    prepared: dict[str, Any] = {}
    for user in VIRTUAL_USERS:
        prepared[str(user)] = _prepare_user(serial, user)
        for loop in range(1, loops + 1):
            for case in TASK_CASES:
                result = _run_case_with_retry(serial, raw_dir, user, loop, case)
                results.append(result)
                print(
                    f"user={user} loop={loop:03d} gate={case[0]} "
                    f"pass={result.get('pass')} attempt={result.get('attempt', 1)}",
                    flush=True,
                )
                if not result.get("pass"):
                    # The matrix is fail-closed, but continue collecting the remaining cases so
                    # the receipt has enough raw evidence to classify the failure batch.
                    continue
    expected = len(VIRTUAL_USERS) * loops * len(TASK_CASES)
    passed = sum(1 for result in results if result.get("pass") is True)
    failed = [
        {
            "user": result.get("user"),
            "loop": result.get("loop"),
            "gate": result.get("gate"),
            "classification": result.get("classification", "RUNTIME_OR_HARNESS_FAILURE"),
            "error": result.get("error", "semantic gate failed"),
            "semantic_verdict": result.get("semantic_verdict"),
        }
        for result in results
        if result.get("pass") is not True
    ]
    return {
        "environment": environment,
        "prepared": prepared,
        "loops_per_user": loops,
        "virtual_users": list(VIRTUAL_USERS),
        "cases": [gate for gate, *_ in TASK_CASES],
        "expected_case_count": expected,
        "observed_case_count": len(results),
        "passed_case_count": passed,
        "failed_case_count": len(failed),
        "overall_pass": len(results) == expected and not failed
        and all(prepared[str(user)].get("pass") for user in VIRTUAL_USERS),
        "failures": failed,
        "results": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default=DEFAULT_INSTANCE)
    parser.add_argument("--loops", type=int, default=DEFAULT_LOOPS)
    parser.add_argument(
        "--receipt",
        type=Path,
        default=ROOT / "verification/catch-up/C1-T01/c1-t01-rd-summary.json",
    )
    args = parser.parse_args()
    if args.loops < 1 or args.loops > 50:
        raise SystemExit("--loops must be between 1 and 50")

    identity = git_identity()
    environment = resolve_rd_environment(args.instance)
    serial = environment["adb_serial"]
    raw_dir = artifacts_dir("catch-up-c1-t01")
    install = install_rd_apks(serial)
    matrix = run_matrix(serial, environment, raw_dir, args.loops)
    receipt = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "host_os": host_os(),
        "git": identity,
        "maturity_level": "RD_BASELINE",
        "va_pro_equivalent": "NOT_PROVEN",
        "instance_name": args.instance,
        "device": environment,
        "apk": apk_metadata(),
        "install": install,
        "raw_evidence_dir": str(raw_dir),
        "matrix": matrix,
    }
    write_json(args.receipt, receipt)
    print(json.dumps({
        "receipt": str(args.receipt),
        "raw_evidence_dir": str(raw_dir),
        "overall_pass": matrix["overall_pass"],
        "passed_case_count": matrix["passed_case_count"],
        "expected_case_count": matrix["expected_case_count"],
        "failed_case_count": matrix["failed_case_count"],
    }, ensure_ascii=False, indent=2))
    return 0 if matrix["overall_pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
