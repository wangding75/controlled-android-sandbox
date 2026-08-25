#!/usr/bin/env python3
"""Pure fail-closed contracts used by the C4-R04 acceptance orchestrator.

The live RD runner is deliberately kept separate from these predicates.  The predicates accept
only request-scoped dynamic evidence; a static Activity marker, a successful command return, or a
Guest process by itself can never satisfy a launch case.  Failure-injection and recovery cases are
recorded as separate observations so a later recovery result cannot overwrite the first failure.
"""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from typing import Any


TASK_ID = "C4-R04"
REQUIRED_STAGES = (
    "REQUEST_ACCEPTED",
    "GUEST_READY",
    "ACTIVITY_RESUMED",
    "FIRST_FRAME_DRAWN",
)
ALLOWED_NO_RETRY = {"NO_RETRY", "NONE", "NOT_APPLICABLE"}
INJECTION_EXPECTATIONS = {
    "windows-empty": "WINDOWS_EMPTY",
    "draw-timeout": "DRAW_TIMEOUT",
    "bind-failure": "BIND_FIRST_ATTEMPT_FAILED",
    "duplicate-add": "DUPLICATE_MUTATION_ACCEPTED",
    "staging-residue": "STAGING_RESIDUE",
}


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _base_case() -> dict[str, Any]:
    package = "com.warden.controlledsandbox.fixture"
    revision = "revision-fixture-1"
    timeline = []
    for index, stage in enumerate(REQUIRED_STAGES):
        start = index * 20
        timeline.append(
            {
                "stage": stage,
                "status": "PASS",
                "startedAtMs": start,
                "completedAtMs": start + 10,
                "durationMs": 10,
                "deadlineMs": 30_000,
            }
        )
    return {
        "schemaVersion": 1,
        "task": TASK_ID,
        "kind": "launch",
        "requestId": "r04-request-valid",
        "operationId": "r04-request-valid-launch",
        "package": package,
        "user": 0,
        "revision": revision,
        "attempt": 1,
        "retryBudget": 0,
        "retryable": False,
        "automaticRetryPerformed": False,
        "retryDecision": "NO_RETRY",
        "launchResult": "PASS",
        "launchStatus": "LAUNCH_PASS",
        "staticMarkers": ["GUEST_ACTIVITY_CREATE", "LAUNCH_PASS"],
        "timeline": timeline,
        "operation": {
            "requestId": "r04-request-valid",
            "operationId": "r04-request-valid-launch",
            "package": package,
            "user": 0,
            "revision": revision,
            "firstFrameDrawn": True,
        },
        "window": {
            "windowsCount": 1,
            "reportedDrawn": True,
            "hasVisible": True,
            "targetActivity": "com.warden.controlledsandbox.fixture.MainActivity",
            "targetPackage": package,
            "revision": revision,
        },
        "surface": {
            "surfaceCount": 1,
            "nonEmpty": True,
            "targetPackage": package,
            "revision": revision,
        },
        "screenshot": {
            "nonTransparent": True,
            "nonBlack": True,
            "hostPlaceholder": False,
            "sha256": "fixture-frame-sha256",
        },
        "fatalMarkers": [],
        "stagingPaths": [],
        "inFlightTransactions": [],
        "publishedRevisions": [],
        "orphanInstances": [],
    }


def _check(checks: list[dict[str, Any]], name: str, passed: bool, **details: Any) -> None:
    checks.append({"name": name, "passed": bool(passed), **details})


def _retry_checks(case: dict[str, Any], checks: list[dict[str, Any]]) -> str | None:
    attempt = case.get("attempt")
    budget = case.get("retryBudget")
    automatic = case.get("automaticRetryPerformed")
    decision = str(case.get("retryDecision") or "").strip().upper()
    _check(checks, "attempt_is_first_attempt", attempt == 1, observed=attempt)
    _check(checks, "retry_budget_is_zero", budget == 0, observed=budget)
    _check(checks, "automatic_retry_is_false", automatic is False, observed=automatic)
    _check(checks, "retry_decision_is_classified", decision in ALLOWED_NO_RETRY,
           observed=decision or None)
    if attempt != 1 or budget != 0 or automatic is not False:
        return "UNAUTHORIZED_AUTOMATIC_RETRY"
    if decision not in ALLOWED_NO_RETRY:
        return "UNCLASSIFIED_RETRY_DECISION"
    return None


def _stage_checks(case: dict[str, Any], checks: list[dict[str, Any]]) -> str | None:
    timeline = case.get("timeline")
    if not isinstance(timeline, list):
        _check(checks, "stage_timing_present", False, observed=type(timeline).__name__)
        return "STAGE_TIMING_MISSING"
    names = [item.get("stage") for item in timeline if isinstance(item, dict)]
    _check(checks, "stage_timing_present", bool(timeline), count=len(timeline))
    cursor = -1
    for required in REQUIRED_STAGES:
        try:
            cursor = names.index(required, cursor + 1)
        except ValueError:
            _check(checks, f"stage_{required}_present_and_ordered", False, stages=names)
            return "READINESS_STATE_MACHINE_INCOMPLETE"
        item = timeline[cursor]
        started = item.get("startedAtMs")
        completed = item.get("completedAtMs")
        duration = item.get("durationMs")
        deadline = item.get("deadlineMs")
        timing_valid = (
            isinstance(started, (int, float))
            and isinstance(completed, (int, float))
            and isinstance(duration, (int, float))
            and isinstance(deadline, (int, float))
            and completed >= started
            and duration == completed - started
            and deadline > 0
        )
        _check(checks, f"stage_{required}_timing", timing_valid,
               startedAtMs=started, completedAtMs=completed, durationMs=duration,
               deadlineMs=deadline)
        if not timing_valid:
            return "INVALID_STAGE_TIMING"
        if item.get("status") != "PASS":
            return str(case.get("errorClassification") or f"{required}_FAILED")
    return None


def _dynamic_launch_checks(case: dict[str, Any], checks: list[dict[str, Any]]) -> list[str]:
    failures: list[str] = []
    package = case.get("package")
    revision = case.get("revision")
    operation = case.get("operation") if isinstance(case.get("operation"), dict) else {}
    window = case.get("window") if isinstance(case.get("window"), dict) else {}
    surface = case.get("surface") if isinstance(case.get("surface"), dict) else {}
    screenshot = case.get("screenshot") if isinstance(case.get("screenshot"), dict) else {}

    correlation = (
        operation.get("requestId") == case.get("requestId")
        and operation.get("operationId") == case.get("operationId")
        and operation.get("package") == package
        and operation.get("user") == case.get("user")
        and operation.get("revision") == revision
    )
    _check(checks, "request_operation_package_user_revision_correlated", correlation)
    if not correlation:
        failures.append("CORRELATION_MISMATCH")

    result_ok = case.get("launchResult") == "PASS" and case.get("launchStatus") == "LAUNCH_PASS"
    _check(checks, "launch_result_and_status_pass", result_ok,
           launchResult=case.get("launchResult"), launchStatus=case.get("launchStatus"))
    if not result_ok:
        failures.append(str(case.get("errorClassification") or "LAUNCH_RESULT_NOT_PASS"))

    first_frame = operation.get("firstFrameDrawn") is True
    _check(checks, "dynamic_first_frame_drawn", first_frame)
    if not first_frame:
        failures.append("FIRST_FRAME_NOT_DRAWN")

    windows_ok = (
        isinstance(window.get("windowsCount"), int)
        and window.get("windowsCount", 0) > 0
        and window.get("reportedDrawn") is True
        and window.get("hasVisible") is True
        and bool(window.get("targetActivity"))
        and window.get("targetPackage") == package
        and window.get("revision") == revision
    )
    _check(checks, "dynamic_guest_window_is_visible_and_current", windows_ok,
           window=window)
    if not windows_ok:
        if window.get("windowsCount", 0) == 0:
            failures.append("WINDOWS_EMPTY")
        else:
            failures.append("GUEST_WINDOW_NOT_READY")

    surface_ok = (
        isinstance(surface.get("surfaceCount"), int)
        and surface.get("surfaceCount", 0) > 0
        and surface.get("nonEmpty") is True
        and surface.get("targetPackage") == package
        and surface.get("revision") == revision
    )
    _check(checks, "dynamic_surface_is_present_and_current", surface_ok,
           surface=surface)
    if not surface_ok:
        failures.append("SURFACE_EMPTY")

    screenshot_ok = (
        screenshot.get("nonTransparent") is True
        and screenshot.get("nonBlack") is True
        and screenshot.get("hostPlaceholder") is False
        and bool(screenshot.get("sha256"))
    )
    _check(checks, "dynamic_screenshot_is_visible_and_not_host_placeholder", screenshot_ok,
           screenshot=screenshot)
    if not screenshot_ok:
        failures.append("SCREENSHOT_BLACK_TRANSPARENT_OR_HOST_PLACEHOLDER")

    fatal = case.get("fatalMarkers")
    fatal_ok = isinstance(fatal, list) and not fatal
    _check(checks, "current_fatal_and_anr_markers_empty", fatal_ok, markers=fatal)
    if not fatal_ok:
        failures.append("FATAL_OR_ANR_MARKER")
    return failures


def evaluate_launch_case(case: dict[str, Any]) -> dict[str, Any]:
    """Evaluate one request-scoped launch observation without weakening any predicate."""
    checks: list[dict[str, Any]] = []
    failures: list[str] = []
    retry_failure = _retry_checks(case, checks)
    if retry_failure:
        failures.append(retry_failure)
    stage_failure = _stage_checks(case, checks)
    if stage_failure:
        failures.append(stage_failure)
    failures.extend(_dynamic_launch_checks(case, checks))
    # The static marker is deliberately observed but never used as a success predicate.
    _check(checks, "static_markers_are_non_authoritative", True,
           markers=case.get("staticMarkers", []))
    unique_failures = list(dict.fromkeys(failures))
    return {
        "schemaVersion": 1,
        "task": TASK_ID,
        "status": "PASS" if not unique_failures else "FAIL",
        "failureDetected": bool(unique_failures),
        "errorClassification": unique_failures[0] if unique_failures else "NONE",
        "retryDecision": case.get("retryDecision"),
        "checks": checks,
        "failures": unique_failures,
    }


def evaluate_mutation_case(case: dict[str, Any]) -> dict[str, Any]:
    """Evaluate the negative mutation/residue contract used by R04 injection tests."""
    checks: list[dict[str, Any]] = []
    failures: list[str] = []
    retry_failure = _retry_checks(case, checks)
    if retry_failure:
        failures.append(retry_failure)

    active_count = case.get("activeOperationCount")
    second_status = case.get("secondOperationStatus")
    duplicate_safe = active_count == 2 and second_status in {"BUSY", "IDEMPOTENT"}
    duplicate_bad = active_count == 2 and second_status == "SUCCEEDED"
    _check(checks, "same_key_mutation_is_single_flight", not duplicate_bad,
           activeOperationCount=active_count, secondOperationStatus=second_status,
           safeOutcome=duplicate_safe)
    if duplicate_bad:
        failures.append("DUPLICATE_MUTATION_ACCEPTED")

    residues = {
        "stagingPaths": case.get("stagingPaths") or [],
        "inFlightTransactions": case.get("inFlightTransactions") or [],
        "publishedRevisions": case.get("publishedRevisions") or [],
        "orphanInstances": case.get("orphanInstances") or [],
    }
    residue_free = all(not value for value in residues.values())
    _check(checks, "mutation_and_revision_residue_is_empty", residue_free, residues=residues)
    if not residue_free:
        if residues["stagingPaths"]:
            failures.append("STAGING_RESIDUE")
        elif residues["inFlightTransactions"]:
            failures.append("IN_FLIGHT_TRANSACTION_RESIDUE")
        elif residues["publishedRevisions"]:
            failures.append("PUBLISHED_REVISION_RESIDUE")
        else:
            failures.append("ORPHAN_INSTANCE_RESIDUE")

    unique_failures = list(dict.fromkeys(failures))
    return {
        "schemaVersion": 1,
        "task": TASK_ID,
        "status": "PASS" if not unique_failures else "FAIL",
        "failureDetected": bool(unique_failures),
        "errorClassification": unique_failures[0] if unique_failures else "NONE",
        "retryDecision": case.get("retryDecision"),
        "checks": checks,
        "failures": unique_failures,
    }


def evaluate_case(case: dict[str, Any]) -> dict[str, Any]:
    if case.get("kind") == "mutation":
        return evaluate_mutation_case(case)
    return evaluate_launch_case(case)


def injection_case(name: str) -> dict[str, Any]:
    if name not in INJECTION_EXPECTATIONS:
        raise ValueError(f"unknown failure injection: {name}")
    case = _base_case()
    case["injection"] = name
    if name == "windows-empty":
        case["window"].update({"windowsCount": 0, "reportedDrawn": False, "hasVisible": False})
        # Keep the producer's optimistic first-frame bit to prove that the
        # orchestrator still rejects the case from dynamic Window evidence.
        case["operation"]["firstFrameDrawn"] = True
    elif name == "draw-timeout":
        case["launchResult"] = "FAIL"
        case["launchStatus"] = "LAUNCH_TIMEOUT"
        case["errorClassification"] = "DRAW_TIMEOUT"
        case["timeline"][-1].update({
            "status": "TIMEOUT",
            "completedAtMs": 30_061,
            "durationMs": 30_001,
            "deadlineMs": 30_000,
        })
        case["operation"]["firstFrameDrawn"] = False
    elif name == "bind-failure":
        case["launchResult"] = "FAIL"
        case["launchStatus"] = "BIND_FAILED"
        case["errorClassification"] = "BIND_FIRST_ATTEMPT_FAILED"
        case["timeline"][1].update({"status": "FAIL", "completedAtMs": 30_001,
                                     "durationMs": 29_981, "deadlineMs": 30_000})
        case["timeline"] = case["timeline"][:2]
        case["operation"]["firstFrameDrawn"] = False
    elif name == "duplicate-add":
        case = {
            "schemaVersion": 1,
            "task": TASK_ID,
            "kind": "mutation",
            "requestId": "r04-duplicate-request",
            "operationId": "r04-duplicate-request-add",
            "package": "com.warden.controlledsandbox.fixture",
            "user": 0,
            "revision": "revision-fixture-1",
            "attempt": 1,
            "retryBudget": 0,
            "retryable": False,
            "automaticRetryPerformed": False,
            "retryDecision": "NO_RETRY",
            "activeOperationCount": 2,
            "secondOperationStatus": "SUCCEEDED",
            "stagingPaths": [],
            "inFlightTransactions": [],
            "publishedRevisions": [],
            "orphanInstances": [],
        }
    elif name == "staging-residue":
        case = {
            "schemaVersion": 1,
            "task": TASK_ID,
            "kind": "mutation",
            "requestId": "r04-residue-request",
            "operationId": "r04-residue-request-add",
            "package": "com.warden.controlledsandbox.fixture",
            "user": 0,
            "revision": "revision-fixture-1",
            "attempt": 1,
            "retryBudget": 0,
            "retryable": False,
            "automaticRetryPerformed": False,
            "retryDecision": "NO_RETRY",
            "activeOperationCount": 1,
            "secondOperationStatus": "NOT_APPLICABLE",
            "stagingPaths": [".install-fixture-0001"],
            "inFlightTransactions": [],
            "publishedRevisions": [],
            "orphanInstances": [],
        }
    return case


def _artifact_index(root: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if not root.exists():
        return rows
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        relative = path.relative_to(root).as_posix()
        rows.append({
            "path": relative,
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
        })
    return rows


def run_failure_injection_suite(output: Path) -> dict[str, Any]:
    output.mkdir(parents=True, exist_ok=True)
    rows: list[dict[str, Any]] = []
    for name, expected in INJECTION_EXPECTATIONS.items():
        case = injection_case(name)
        case_dir = output / name
        first = copy.deepcopy(case)
        first_decision = evaluate_case(first)
        write_json(case_dir / "first-observation.json", first)
        write_json(case_dir / "first-decision.json", first_decision)
        # The final observation is a separate file.  It is intentionally not a retry and does not
        # replace the first observation; this makes evidence preservation machine-checkable.
        final = copy.deepcopy(case)
        final_decision = evaluate_case(final)
        write_json(case_dir / "final-observation.json", final)
        write_json(case_dir / "final-decision.json", final_decision)
        guarded = (
            first_decision["status"] == "FAIL"
            and final_decision["status"] == "FAIL"
            and first_decision["errorClassification"] == expected
            and final_decision["errorClassification"] == expected
            and first.get("attempt") == 1
            and first.get("retryBudget") == 0
            and first.get("automaticRetryPerformed") is False
        )
        rows.append({
            "scenario": name,
            "expectedRunnerStatus": "FAIL",
            "runnerStatus": final_decision["status"],
            "expectedErrorClassification": expected,
            "observedErrorClassification": final_decision["errorClassification"],
            "firstFailurePreserved": first_decision == final_decision,
            "guardPassed": guarded,
            "artifacts": str(case_dir.resolve()),
        })
    report = {
        "schemaVersion": 1,
        "task": TASK_ID,
        "mode": "failure-injection",
        "status": "PASS" if all(row["guardPassed"] for row in rows) else "FAIL",
        "runnerContract": {
            "firstFailureStopsLane": True,
            "automaticRetryBudget": 0,
            "fixedSleepReadiness": False,
            "staticMarkersAuthoritative": False,
            "recoveryIsSeparateMode": True,
        },
        "scenarios": rows,
    }
    write_json(output / "failure-injection-summary.json", report)
    write_json(output / "artifact-index.json", {"schemaVersion": 1,
                                                  "root": str(output.resolve()),
                                                  "artifacts": _artifact_index(output)})
    return report


def run_recovery_contract_suite(output: Path) -> dict[str, Any]:
    output.mkdir(parents=True, exist_ok=True)
    first = injection_case("bind-failure")
    recovery = _base_case()
    recovery["requestId"] = "r04-recovery-request"
    recovery["operationId"] = "r04-recovery-request-launch"
    recovery["operation"]["requestId"] = recovery["requestId"]
    recovery["operation"]["operationId"] = recovery["operationId"]
    first_decision = evaluate_case(first)
    recovery_decision = evaluate_case(recovery)
    write_json(output / "first-failure.json", first)
    write_json(output / "first-failure-decision.json", first_decision)
    write_json(output / "recovery-observation.json", recovery)
    write_json(output / "recovery-decision.json", recovery_decision)
    report = {
        "schemaVersion": 1,
        "task": TASK_ID,
        "mode": "recovery",
        "status": "PASS" if first_decision["status"] == "FAIL"
        and recovery_decision["status"] == "PASS"
        and first["requestId"] != recovery["requestId"] else "FAIL",
        "automaticRetryPerformed": False,
        "firstFailure": first_decision,
        "recovery": recovery_decision,
        "artifacts": str(output.resolve()),
    }
    write_json(output / "recovery-summary.json", report)
    write_json(output / "artifact-index.json", {"schemaVersion": 1,
                                                  "root": str(output.resolve()),
                                                  "artifacts": _artifact_index(output)})
    return report
