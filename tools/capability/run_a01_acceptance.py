#!/usr/bin/env python3
"""T57-R03-P4-FIX02-A01 Full Acceptance Matrix Runner (fail-closed)."""

from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ROOT, artifacts_dir, git_identity, host_os, now_iso, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import HOST_PACKAGE, apk_metadata, run_adb

CAMPAIGN_ID = "T57-R03-P4-FIX02-A01"
TRUST = ("--ez", "trustNativeGuest", "true")
SCALE_INDICES = (0, 63, 64, 95, 127)
REQUIRED_API_LEVELS = ("32", "35", "36")
TASK_EVIDENCE_PREFIX = "FRAMEWORK_TASK_EVIDENCE "

TASK_EVIDENCE_REQUIREMENTS = {
    "standard": ("created_two_records", "on_create_twice", "top_activity_correct", "back_stack_correct"),
    "single_top_top": ("on_new_intent", "no_second_on_create", "top_activity_correct"),
    "single_top_non_top": ("new_activity_created", "no_reuse", "top_activity_correct"),
    "single_task": ("child_cleared_by_framework", "physical_record_reused", "on_new_intent",
                     "no_second_on_create", "resumed", "back_stack_correct"),
    "clear_top_standard": ("target_destroyed", "child_removed", "target_recreated",
                            "no_on_new_intent"),
    "clear_top_single_top": ("child_removed", "target_reused", "on_new_intent",
                              "no_on_create"),
    "reorder_to_front": ("stopped_before_request", "started_after_request", "resumed_after_request",
                          "physical_top_component", "activity_record_stack_order",
                          "virtual_token_mapping", "no_second_on_create", "back_stack_correct"),
}

# Every one of these gates must pass or the whole matrix is failed closed.  A single
# FAIL in scale, basic launch, ActivityResult, any task-mode semantic, process death,
# session fencing, Service, Provider or PendingIntent turns the runner exit code non-zero.
REQUIRED_GATES = (
    "scale",
    "basic_launch",
    "activity_result",
    "standard",
    "single_top",
    "single_top_non_top",
    "single_task",
    "clear_top_standard",
    "clear_top",
    "reorder_to_front",
    "process_death",
    "session_fencing",
    "service",
    "provider",
    "pending_intent",
)


def check_logcat_marker(
    serial: str,
    pass_marker: str,
    fail_marker: str | None = None,
    wait_sec: float = 2.0,
) -> dict[str, Any]:
    time.sleep(wait_sec)
    try:
        logcat = run_adb(serial, ["logcat", "-d", "-v", "threadtime"], check=False).stdout or ""
    except Exception:
        logcat = ""
    has_pass = pass_marker in logcat
    has_fail = (fail_marker in logcat) if fail_marker else False
    if has_fail:
        verdict = "FIXTURE_SEMANTIC_FAIL"
    elif has_pass:
        verdict = "FIXTURE_SEMANTIC_PASS"
    else:
        verdict = "FIXTURE_SEMANTIC_TIMEOUT"
    return {
        "verdict": verdict,
        "pass_marker_found": has_pass,
        "fail_marker_found": has_fail,
    }


def parse_task_semantic_evidence(logcat: str, case: str) -> dict[str, Any]:
    """Parse structured task evidence; legacy PASS markers are intentionally ignored."""
    records: list[dict[str, Any]] = []
    for line in (logcat or "").splitlines():
        marker = line.find(TASK_EVIDENCE_PREFIX)
        if marker < 0:
            continue
        payload = line[marker + len(TASK_EVIDENCE_PREFIX):].strip()
        try:
            evidence = json.loads(payload)
        except json.JSONDecodeError:
            continue
        if isinstance(evidence, dict) and evidence.get("case") == case:
            records.append(evidence)
    if not records:
        return {"verdict": "FIXTURE_SEMANTIC_TIMEOUT", "evidence": None,
                "missing_fields": list(TASK_EVIDENCE_REQUIREMENTS.get(case, ())),
                "legacy_marker_ignored": True}
    evidence = records[-1]
    required = TASK_EVIDENCE_REQUIREMENTS.get(case, ())
    missing = [field for field in required if evidence.get(field) is not True]
    passed = evidence.get("pass") is True and not missing
    return {
        "verdict": "FIXTURE_SEMANTIC_PASS" if passed else "FIXTURE_SEMANTIC_FAIL",
        "evidence": evidence,
        "missing_fields": missing,
        "legacy_marker_ignored": True,
    }


def evaluate_required_api_matrix(devices: list[dict[str, Any]]) -> tuple[bool, list[str]]:
    """Require every frozen API level and a passing semantic matrix for each level."""
    failed: list[str] = []
    by_api: dict[str, list[dict[str, Any]]] = {}
    for device in devices if isinstance(devices, list) else []:
        if isinstance(device, dict):
            by_api.setdefault(str(device.get("api", "")), []).append(device)
    for api in REQUIRED_API_LEVELS:
        entries = by_api.get(api, [])
        if not entries:
            failed.append(f"missing_api_{api}")
        elif not any(entry.get("overall_pass") is True for entry in entries):
            failed.append(f"api_{api}_semantic")
    return not failed, failed


def capture_activity_evidence(serial: str, device_dir: Path, case: str, phase: str,
                              command: dict[str, Any] | None = None,
                              logcat: str | None = None) -> Path:
    """Persist per-case before/transition/after evidence instead of one final dump."""
    safe_case = re.sub(r"[^A-Za-z0-9_.-]+", "_", case)
    safe_phase = re.sub(r"[^A-Za-z0-9_.-]+", "_", phase)
    path = device_dir / f"{serial.replace(':', '_')}-{safe_case}-{safe_phase}.json"
    if phase == "transition":
        payload: dict[str, Any] = {"case": case, "phase": phase, "request": command or {},
                                   "lifecycle_logcat": logcat or ""}
    else:
        dumpsys = run_adb(serial, ["shell", "dumpsys", "activity", "activities"], check=False).stdout or ""
        payload = {"case": case, "phase": phase, "dumpsys_activity_activities": dumpsys,
                   "topResumedActivity_lines": [line for line in dumpsys.splitlines()
                                                 if "topResumedActivity" in line],
                   "task_stack_lines": [line for line in dumpsys.splitlines()
                                        if "* Task" in line or "Hist #" in line]}
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    return path


def evaluate_gates(tests: dict[str, Any]) -> tuple[bool, list[str]]:
    """Pure, deterministic required-gate aggregation.

    Returns ``(overall_pass, failed_gates)``.  Any required gate whose recorded ``pass``
    value is not ``True`` fails the whole matrix.  This function has no device dependency so
    the deterministic runner test can exercise it in isolation.
    """
    failed: list[str] = []
    for gate in REQUIRED_GATES:
        entry = tests.get(gate) if isinstance(tests, dict) else None
        passed = isinstance(entry, dict) and entry.get("pass") is True
        if not passed:
            failed.append(gate)
    return (not failed), failed


def safe_debug_command(serial: str, extras: list[str], deadline_sec: int = 60,
                       force_stop_host: bool = True) -> dict[str, Any]:
    try:
        return debug_command(serial, extras, deadline_sec=deadline_sec,
                             force_stop_host=force_stop_host)
    except Exception as error:
        return {"status": "ERROR", "returncode": 1,
                "detail": f"{error.__class__.__name__}: {error}"}


def run_device_matrix(serial: str, api: str, model: str) -> dict[str, Any]:
    print(f"\n==========================================")
    print(f"Running A01 Acceptance Matrix on {serial} (API {api}, Model {model})")
    print(f"==========================================")

    device_dir = artifacts_dir("a01-acceptance")
    tests: dict[str, Any] = {}

    run_adb(serial, ["logcat", "-c"], check=False)

    # 1. Scale boundary indices (0, 63, 64, 95, 127).  095 is singleTask and must not be ignored.
    scale_results = {}
    for idx in SCALE_INDICES:
        comp = f"com.warden.controlledsandbox.fixture.scale.ScaleActivity{idx:03d}"
        r = safe_debug_command(
            serial,
            ["-e", "command", "launch-component", "-e", "package",
             "com.warden.controlledsandbox.fixture.scale", "-e", "component", comp, *TRUST],
            deadline_sec=60,
        )
        status = r.get("status")
        scale_results[f"ScaleActivity{idx:03d}"] = {
            "status": status,
            "operation": (r.get("result") or {}).get("operation"),
        }
    tests["scale"] = {
        "results": scale_results,
        "pass": all(v.get("status") == "PASS" for v in scale_results.values()),
    }

    # 2. Basic package / Activity launch
    pkg = "com.warden.controlledsandbox.fixture.scale" if api == "36" else "com.warden.controlledsandbox.fixture"
    r_basic = safe_debug_command(serial, ["-e", "command", "import-launch", "-e", "package", pkg, *TRUST], deadline_sec=60)
    tests["basic_launch"] = {
        "package": pkg,
        "status": r_basic.get("status"),
        "operation": (r_basic.get("result") or {}).get("operation"),
        "pass": r_basic.get("status") == "PASS",
    }

    # 3. ActivityResult delivery
    run_adb(serial, ["logcat", "-c"], check=False)
    comp_result = "com.warden.controlledsandbox.fixture.FrameworkActivityResultParentActivity"
    r_result = safe_debug_command(
        serial,
        ["-e", "command", "launch-component", "-e", "package",
         "com.warden.controlledsandbox.fixture", "-e", "component", comp_result, *TRUST],
        deadline_sec=60,
    )
    result_marker = check_logcat_marker(
        serial, "FRAMEWORK_PROBE_ACTIVITY_RESULT_PASS", "FRAMEWORK_PROBE_ACTIVITY_RESULT_FAIL", wait_sec=8.0,
    )
    tests["activity_result"] = {
        "component": comp_result,
        "command_status": r_result.get("status"),
        "semantic_verdict": result_marker["verdict"],
        "pass": r_result.get("status") == "PASS" and result_marker["verdict"] == "FIXTURE_SEMANTIC_PASS",
    }

    # 4. Task Mode Matrix (standard, singleTop, singleTask, CLEAR_TOP, REORDER_TO_FRONT)
    task_matrix: dict[str, Any] = {}
    for gate, comp, evidence_case, pass_marker, fail_marker, wait_sec in (
        ("standard", "com.warden.controlledsandbox.fixture.StandardTaskProbeActivity",
         "standard",
         "FRAMEWORK_PROBE_TASK_STANDARD_PASS", "FRAMEWORK_PROBE_TASK_STANDARD_FAIL", 2.0),
        ("single_top", "com.warden.controlledsandbox.fixture.SingleTopProbeActivity",
         "single_top_top",
         "FRAMEWORK_PROBE_TASK_SINGLETOP_PASS", "FRAMEWORK_PROBE_TASK_SINGLETOP_FAIL", 2.0),
        ("single_top_non_top", "com.warden.controlledsandbox.fixture.SingleTopNonTopProbeActivity",
         "single_top_non_top",
         "FRAMEWORK_PROBE_TASK_SINGLETOP_NONTOP_PASS", "FRAMEWORK_PROBE_TASK_SINGLETOP_NONTOP_FAIL", 6.0),
        ("single_task", "com.warden.controlledsandbox.fixture.TaskSemanticsProbeActivity",
         "single_task",
         "FRAMEWORK_PROBE_TASK_REUSE_PASS", "FRAMEWORK_PROBE_TASK_REUSE_FAIL", 8.0),
        ("clear_top_standard", "com.warden.controlledsandbox.fixture.ClearTopStandardProbeActivity",
         "clear_top_standard",
         "FRAMEWORK_PROBE_TASK_CLEAR_TOP_STANDARD_PASS", "FRAMEWORK_PROBE_TASK_CLEAR_TOP_STANDARD_FAIL", 6.0),
        ("clear_top", "com.warden.controlledsandbox.fixture.ClearTopProbeActivity",
         "clear_top_single_top",
         "FRAMEWORK_PROBE_TASK_CLEAR_TOP_PASS", "FRAMEWORK_PROBE_TASK_CLEAR_TOP_FAIL", 8.0),
        ("reorder_to_front", "com.warden.controlledsandbox.fixture.ReorderToFrontProbeActivity",
         "reorder_to_front",
         "FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_PASS", "FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_FAIL", 8.0),
    ):
        run_adb(serial, ["logcat", "-c"], check=False)
        capture_activity_evidence(serial, device_dir, gate, "before")
        r = safe_debug_command(
            serial,
            ["-e", "command", "launch-component", "-e", "package",
             "com.warden.controlledsandbox.fixture", "-e", "component", comp, *TRUST],
            deadline_sec=60,
        )
        time.sleep(wait_sec)
        task_logcat = run_adb(serial, ["logcat", "-d", "-v", "threadtime"], check=False).stdout or ""
        semantic = parse_task_semantic_evidence(task_logcat, evidence_case)
        capture_activity_evidence(serial, device_dir, gate, "transition", r, task_logcat)
        capture_activity_evidence(serial, device_dir, gate, "after")
        task_matrix[gate] = {
            "component": comp,
            "command_status": r.get("status"),
            "semantic_verdict": semantic["verdict"],
            "structured_evidence": semantic,
            "legacy_marker_pass_not_used": True,
            "pass": r.get("status") == "PASS" and semantic["verdict"] == "FIXTURE_SEMANTIC_PASS",
        }
        print(f"  -> {gate}: {semantic['verdict']}")
    tests.update(task_matrix)

    # 5. Process death and real stale-session fencing
    r_proc1 = safe_debug_command(
        serial,
        ["-e", "command", "import-prepare", "-e", "package", "com.warden.controlledsandbox.fixture", *TRUST],
        deadline_sec=60,
    )
    op1 = (r_proc1.get("result") or {}).get("operation") or {}
    sess1 = op1.get("sessionId", "")
    gen1 = op1.get("generation", 0)
    pid1 = op1.get("platformPid", 0) or op1.get("pid", 0)

    if pid1:
        run_adb(serial, ["shell", "kill", "-9", str(pid1)], check=False)

    r_proc2 = safe_debug_command(
        serial,
        ["-e", "command", "import-prepare", "-e", "package", "com.warden.controlledsandbox.fixture", *TRUST],
        deadline_sec=60,
        force_stop_host=False,
    )
    op2 = (r_proc2.get("result") or {}).get("operation") or {}
    sess2 = op2.get("sessionId", "")
    gen2 = op2.get("generation", 0)
    pid2 = op2.get("platformPid", 0) or op2.get("pid", 0)

    pid_relaunch_pass = r_proc2.get("status") == "PASS" and pid2 != 0 and pid2 != pid1

    # Real stale request: send a generation-fenced broker operation with the OLD identity and
    # require an explicit rejection, never a local sessionId/generation comparison.
    stale_probe = {}
    stale_rejection = False
    if sess1 and gen1 and (sess1 != sess2 or gen1 != gen2):
        r_stale = safe_debug_command(
            serial,
            ["-e", "command", "stale-session", "-e", "package",
             "com.warden.controlledsandbox.fixture", "-e", "staleSessionId", sess1,
             "--el", "staleGeneration", str(gen1), *TRUST],
            deadline_sec=60,
        )
        stale_probe = (r_stale.get("result") or {}).get("staleSessionProbe") or \
            (r_stale.get("result") or {}).get("operation") or {}
        # The debug command fails closed (status FAIL) when the stale request is accepted, so a
        # PASS command result together with accepted=false proves the Broker rejected it.
        stale_rejection = r_stale.get("status") == "PASS" and stale_probe.get("accepted") is False

    tests["process_death"] = {"pass": pid_relaunch_pass}
    tests["session_fencing"] = {
        "old_session": sess1,
        "old_generation": gen1,
        "new_session": sess2,
        "new_generation": gen2,
        "stale_probe": stale_probe,
        "pass": stale_rejection,
    }

    # 6. Neighbor smoke: real Service start, real Provider prepare/query, real PendingIntent.
    r_service = safe_debug_command(
        serial,
        ["-e", "command", "neighbor-service", "-e", "package", "com.warden.controlledsandbox.fixture", *TRUST],
        deadline_sec=60,
    )
    r_provider = safe_debug_command(
        serial,
        ["-e", "command", "neighbor-provider", "-e", "package", "com.warden.controlledsandbox.fixture", *TRUST],
        deadline_sec=60,
    )
    r_pi = safe_debug_command(
        serial,
        ["-e", "command", "pi-system-holder", "-e", "package", "com.warden.controlledsandbox.fixture", *TRUST],
        deadline_sec=60,
    )
    tests["service"] = {
        "status": r_service.get("status"),
        "operation": (r_service.get("result") or {}).get("operation"),
        "pass": r_service.get("status") == "PASS",
    }
    tests["provider"] = {
        "status": r_provider.get("status"),
        "operation": (r_provider.get("result") or {}).get("operation"),
        "pass": r_provider.get("status") == "PASS",
    }
    tests["pending_intent"] = {
        "status": r_pi.get("status"),
        "operation": (r_pi.get("result") or {}).get("operation"),
        "pass": r_pi.get("status") == "PASS",
    }

    overall, failed = evaluate_gates(tests)
    logcat_out = run_adb(serial, ["logcat", "-d", "-v", "threadtime"], check=False).stdout or ""
    safe_serial = serial.replace(":", "_").replace(".", "_")
    (device_dir / f"{safe_serial}-logcat.txt").write_text(logcat_out, encoding="utf-8", errors="replace")
    dumpsys_out = run_adb(serial, ["shell", "dumpsys", "activity", "activities"], check=False).stdout or ""
    (device_dir / f"{safe_serial}-dumpsys.txt").write_text(dumpsys_out, encoding="utf-8", errors="replace")

    return {
        "serial": serial,
        "api": api,
        "model": model,
        "tests": tests,
        "overall_pass": overall,
        "failed_gates": failed,
    }


def main() -> int:
    devices_out = run_adb(None, ["devices", "-l"], check=False).stdout or ""
    device_rows = []
    for line in devices_out.splitlines():
        if "\tdevice" not in line and " device " not in line:
            continue
        serial = line.split()[0]
        if serial == "List":
            continue
        api = run_adb(serial, ["shell", "getprop", "ro.build.version.sdk"], check=False).stdout.strip()
        model = run_adb(serial, ["shell", "getprop", "ro.product.model"], check=False).stdout.strip()
        device_rows.append((serial, api, model))

    print(f"Found {len(device_rows)} devices: {device_rows}")

    out_dir = artifacts_dir("a01-acceptance")
    all_results = []
    for serial, api, model in device_rows:
        try:
            res = run_device_matrix(serial, api, model)
        except Exception as error:
            res = {
                "serial": serial,
                "api": api,
                "model": model,
                "tests": {},
                "overall_pass": False,
                "failed_gates": list(REQUIRED_GATES),
                "error": f"{error.__class__.__name__}: {error}",
            }
            print(f"[FAIL-CLOSED] {serial}: {error.__class__.__name__}: {error}")
        all_results.append(res)

    api_matrix_pass, api_matrix_failed = evaluate_required_api_matrix(all_results)
    overall_pass = bool(all_results) and all(res.get("overall_pass") for res in all_results) \
        and api_matrix_pass
    failed_gates: list[str] = []
    for res in all_results:
        failed_gates.extend(res.get("failed_gates", []))
    failed_gates.extend(api_matrix_failed)

    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "apk": apk_metadata(),
        "devices": all_results,
        "overall_pass": overall_pass,
        "failed_gates": failed_gates,
        "required_api_levels": list(REQUIRED_API_LEVELS),
        "required_api_matrix_pass": api_matrix_pass,
    }
    write_json(out_dir / "evidence.json", evidence)
    print("\n==========================================")
    print(f"A01 Acceptance Suite complete.")
    print(f"OVERALL_PASS={overall_pass}")
    print(f"FAILED_GATES={failed_gates}")
    print(f"Evidence written to {out_dir / 'evidence.json'}")
    print("==========================================")
    print(json.dumps(all_results, indent=2))
    return 0 if overall_pass else 1


if __name__ == "__main__":
    raise SystemExit(main())
