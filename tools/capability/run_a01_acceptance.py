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
from aggregate_a01_matrix import aggregate_matrix, canonical_evidence_sha256
from run_p1_00_rd import debug_command
from run_rd_campaign import HOST_PACKAGE, apk_metadata, run_adb

CAMPAIGN_ID = "T57-R03-P4-FIX02-A01"
TRUST = ("--ez", "trustNativeGuest", "true")
SCALE_INDICES = (0, 63, 64, 95, 127)
REQUIRED_API_LEVELS = ("32", "35", "36")
TASK_EVIDENCE_PREFIX = "FRAMEWORK_TASK_EVIDENCE "

TASK_EVIDENCE_REQUIREMENTS = {
    "standard": ("created_two_records", "top_activity_correct", "back_stack_correct"),
    "single_top_top": ("on_new_intent", "no_second_on_create", "top_activity_correct"),
    "single_top_non_top": ("new_activity_created", "no_reuse", "top_activity_correct"),
    "single_task": ("child_cleared_by_framework", "physical_record_reused", "on_new_intent",
                    "no_second_on_create", "resumed", "back_stack_correct"),
    "clear_top_standard": ("target_destroyed", "child_removed", "target_recreated",
                           "no_on_new_intent"),
    "clear_top_single_top": ("child_removed", "target_reused", "on_new_intent",
                             "no_on_create"),
    "reorder_to_front": ("stopped_before_request", "started_after_request",
                         "resumed_after_request", "physical_top_component",
                         "activity_record_stack_order", "virtual_token_mapping",
                         "no_second_on_create", "back_stack_correct"),
}

FORBIDDEN_FIXTURE_PROOF_FIELDS = {
    "pass", "top_activity_correct", "back_stack_correct", "child_cleared_by_framework",
    "physical_record_reused", "physical_top_component", "activity_record_stack_order",
    "virtual_token_mapping", "resumed", "created_two_records", "on_create_twice",
    "target_destroyed", "child_removed", "target_recreated", "no_on_new_intent",
    "target_reused", "no_on_create", "stopped_before_request", "started_after_request",
    "resumed_after_request", "no_second_on_create", "new_activity_created", "no_reuse",
    "on_new_intent",
}

TASK_FIXTURE_LIFECYCLE = {
    "standard": {"create": 2, "new_intent": 0, "start": 2, "resume": 3},
    # A top singleTop delivery stays resumed on Android; the required post-transition
    # observation is onNewIntent plus the real Back/dumpsys proof below.
    "single_top_top": {"create": 1, "new_intent": 1},
    "single_top_non_top": {"create": 2, "new_intent": 0},
    "single_task": {"create": 1, "new_intent": 1, "start": 2, "resume": 2},
    "clear_top_standard": {"create": 2, "new_intent": 0, "destroy": 1, "resume": 2},
    "clear_top_single_top": {"create": 1, "new_intent": 1, "start": 2, "resume": 2},
    "reorder_to_front": {"create": 1, "new_intent": 1, "start": 2, "resume": 2, "stop": 1},
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
    """Parse fixture observations; semantic booleans are intentionally rejected."""
    records: list[dict[str, Any]] = []
    for line_no, line in enumerate((logcat or "").splitlines()):
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
    forbidden = sorted(FORBIDDEN_FIXTURE_PROOF_FIELDS.intersection(evidence))
    lifecycle = evidence.get("lifecycle")
    missing = []
    if not isinstance(lifecycle, dict):
        missing.append("lifecycle")
    else:
        missing.extend(field for field in ("onCreate", "onNewIntent", "onStart", "onResume",
                                           "onStop", "onDestroy") if field not in lifecycle)
    missing.extend(field for field in ("route_token", "activity_token") if not evidence.get(field))
    passed = not missing and not forbidden
    return {
        "verdict": "FIXTURE_OBSERVATION" if passed else "FIXTURE_SEMANTIC_FAIL",
        "evidence": evidence,
        "missing_fields": missing,
        "forbidden_fields": forbidden,
        "legacy_marker_ignored": True,
    }


def parse_task_events(logcat: str, case: str) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    prefix = "FRAMEWORK_TASK_EVENT "
    for line in (logcat or "").splitlines():
        marker = line.find(prefix)
        if marker < 0:
            continue
        try:
            value = json.loads(line[marker + len(prefix):].strip())
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and value.get("case") == case:
            events.append(value)
    return events


def _key_values(line: str) -> dict[str, str]:
    return {key: value for key, value in re.findall(r"([A-Za-z][A-Za-z0-9_]*)=([^\s]+)", line)}


def parse_runtime_activity_events(logcat: str) -> list[dict[str, Any]]:
    """Parse runtime activity diagnostics without trusting fixture assertions."""
    events: list[dict[str, Any]] = []
    for line in (logcat or "").splitlines():
        marker = line.find("CS_RUNTIME: ")
        if marker < 0:
            continue
        body = line[marker + len("CS_RUNTIME: "):].strip()
        name = body.split(None, 1)[0] if body else ""
        if not (name.startswith("ATMS_ACTIVITY_") or name.startswith("GUEST_ACTIVITY_")):
            continue
        row = {"event": name, "line_no": line_no}
        row.update(_key_values(body))
        events.append(row)
    return events


def _normal_component(value: str) -> str:
    value = str(value or "")
    return value.rsplit("/", 1)[-1]


def _component_matches(actual: str, physical: str) -> bool:
    actual = _normal_component(actual)
    physical = _normal_component(physical)
    return bool(actual and physical and (actual == physical or actual.endswith(physical)))


def parse_activity_dumpsys(dumpsys: str) -> dict[str, Any]:
    """Extract topResumedActivity and ActivityRecord ordering from dumpsys activity activities."""
    text = dumpsys or ""
    top = re.search(
        r"topResumedActivity=ActivityRecord\{(?P<record>[0-9a-f]+)\s+u\d+\s+"
        r"(?P<component>\S+)\s+t(?P<task>\d+)",
        text,
    )
    history: list[dict[str, Any]] = []
    history_pattern = re.compile(
        r"\* Hist\s+#(?P<index>\d+):\s+ActivityRecord\{"
        r"(?P<record>[0-9a-f]+)\s+u\d+\s+(?P<component>\S+)\s+t(?P<task>\d+)"
    )
    for match in history_pattern.finditer(text):
        history.append({
            "index": int(match.group("index")),
            "record": match.group("record"),
            "component": match.group("component"),
            "task": int(match.group("task")),
        })
    return {
        "present": bool(text.strip()),
        "top_resumed": top.groupdict() if top else None,
        "history": history,
    }


def _fixture_lifecycle_pass(case: str, observation: dict[str, Any] | None,
                            events: list[dict[str, Any]]) -> tuple[bool, dict[str, Any]]:
    lifecycle = (observation or {}).get("lifecycle") if isinstance(observation, dict) else None
    counts = {
        "create": int((lifecycle or {}).get("onCreate", -1)),
        "new_intent": int((lifecycle or {}).get("onNewIntent", -1)),
        "start": int((lifecycle or {}).get("onStart", -1)),
        "resume": int((lifecycle or {}).get("onResume", -1)),
        "stop": int((lifecycle or {}).get("onStop", -1)),
        "destroy": int((lifecycle or {}).get("onDestroy", -1)),
    }
    required = TASK_FIXTURE_LIFECYCLE.get(case, {})
    checks = {key: counts.get(key, -1) >= value for key, value in required.items()}
    if "new_intent" in required and required["new_intent"] == 0:
        checks["new_intent"] = counts["new_intent"] == 0
    if case == "clear_top_standard":
        checks["new_intent"] = counts["new_intent"] == 0
    checks["observation_present"] = observation is not None
    checks["back_requested"] = any(event.get("event") == "BACK_REQUEST" for event in events)
    checks["back_completed"] = any(event.get("event") == "BACK_COMPLETE" for event in events)
    return all(checks.values()), {"counts": counts, "checks": checks}


def evaluate_task_system_evidence(
    case: str,
    component: str,
    fixture_semantic: dict[str, Any],
    before_payload: dict[str, Any],
    transition_payload: dict[str, Any],
    after_payload: dict[str, Any],
) -> dict[str, Any]:
    """Calculate task semantics exclusively from fixture observations plus system evidence."""
    observation = fixture_semantic.get("evidence") if isinstance(fixture_semantic, dict) else None
    logcat = str(transition_payload.get("lifecycle_logcat", ""))
    runtime_events = parse_runtime_activity_events(logcat)
    task_events = parse_task_events(logcat, case)
    launches = [row for row in runtime_events
                if row["event"] == "ATMS_ACTIVITY_LAUNCH_REQUEST"
                and row.get("component") == component]
    target_guest = [row for row in runtime_events
                    if row["event"].startswith("GUEST_ACTIVITY_")
                    and row.get("component") == component]
    mappings = [row for row in runtime_events
                if row["event"] == "ATMS_ACTIVITY_RECORD_MAPPING"]
    before = parse_activity_dumpsys(before_payload.get("dumpsys_activity_activities", ""))
    transition = parse_activity_dumpsys(
        transition_payload.get("dumpsys_activity_activities", ""))
    after = parse_activity_dumpsys(after_payload.get("dumpsys_activity_activities", ""))
    target_physical = {row.get("physicalActivityComponent", "") for row in launches
                       if row.get("physicalActivityComponent")}
    target_tokens = {row.get("activityToken", "") for row in launches if row.get("activityToken")}
    target_task = next((int(row["taskId"]) for row in launches if row.get("taskId", "").isdigit()), None)
    stack = [row for row in transition["history"]
             if target_task is not None and row["task"] == target_task]
    stack.sort(key=lambda row: row["index"])
    top = transition.get("top_resumed")
    top_is_target = bool(top and any(_component_matches(top.get("component", ""), physical)
                                     for physical in target_physical))
    target_stack = [row for row in stack
                    if any(_component_matches(row["component"], physical)
                           for physical in target_physical)]
    child_stack = [row for row in stack if "DetailActivity" in row["component"]]
    target_after = [row for row in after["history"]
                    if any(_component_matches(row["component"], physical)
                           for physical in target_physical)]
    after_top = after.get("top_resumed")
    after_top_target = bool(after_top and any(
        _component_matches(after_top.get("component", ""), physical)
        for physical in target_physical))
    after_top_detail = bool(after_top and "DetailActivity" in after_top.get("component", ""))
    launch_actions = [row.get("activityAction", "") for row in launches]
    fixture_pass, fixture_details = _fixture_lifecycle_pass(case, observation, task_events)
    guest_names = [row["event"] for row in target_guest]
    new_intent_indices = [index for index, name in enumerate(guest_names)
                          if name == "GUEST_ACTIVITY_NEW_INTENT"]
    resumed_after_new_intent = any(
        any(name == "GUEST_ACTIVITY_RESUMED" for name in guest_names[index + 1:])
        for index in new_intent_indices
    )
    runtime_lifecycle = {
        "counts": {name: guest_names.count(name) for name in (
            "GUEST_ACTIVITY_CREATED", "GUEST_ACTIVITY_STARTED",
            "GUEST_ACTIVITY_RESUMED", "GUEST_ACTIVITY_NEW_INTENT",
            "GUEST_ACTIVITY_STOPPED", "GUEST_ACTIVITY_DESTROYED")},
        "sequence": guest_names,
        "resumed_after_new_intent": resumed_after_new_intent,
    }
    runtime_pass = bool(target_guest)
    if case in {"single_task", "clear_top_single_top", "reorder_to_front"}:
        runtime_pass = runtime_pass and resumed_after_new_intent and (
            "GUEST_ACTIVITY_STARTED" in guest_names
        )
    if case in {"single_top_top"}:
        runtime_pass = runtime_pass and resumed_after_new_intent
    same_physical = len(launches) >= 2 and launches[0].get(
        "physicalActivityComponent") == launches[-1].get("physicalActivityComponent")
    same_virtual = len(launches) >= 2 and launches[0].get(
        "activityToken") == launches[-1].get("activityToken")
    mapping_by_route = {
        (row.get("routeToken"), row.get("activityToken")): row for row in mappings
    }
    mapping_complete = bool(launches) and all(
        row.get("routeToken") and row.get("activityToken")
        and row.get("physicalActivityComponent")
        and mapping_by_route.get((row.get("routeToken"), row.get("activityToken")),
                                 {}).get("frameworkActivityToken")
        and mapping_by_route.get((row.get("routeToken"), row.get("activityToken")),
                                 {}).get("physicalActivityComponent")
        for row in launches
    )
    mapping_record_present = bool(mappings) and any(
        any(_component_matches(record["component"], physical)
            for physical in target_physical)
        for record in transition["history"]
    )
    token_mapping_pass = mapping_complete and mapping_record_present
    physical_record_reused = same_physical and same_virtual
    new_record = len(launches) >= 2 and not same_virtual
    child_cleared = not child_stack
    stack_order = bool(target_stack and target_stack[0]["index"] == min(row["index"] for row in stack))
    if case == "reorder_to_front":
        stack_order = bool(target_stack and child_stack and
                           target_stack[0]["index"] < child_stack[0]["index"])
    transition_dumpsys_pass = before["present"] and transition["present"] and after["present"]
    back_expected = {
        "standard": after_top_target and len(target_after) == 1,
        "single_top_top": not after_top_target,
        "single_top_non_top": after_top_target and len(target_after) == 1,
        "single_task": not after_top_target,
        "clear_top_standard": not after_top_target,
        "clear_top_single_top": not after_top_target,
        "reorder_to_front": after_top_detail and not after_top_target,
    }
    back_stack_pass = (
        any(event.get("event") == "BACK_REQUEST" for event in task_events)
        and any(event.get("event") == "BACK_COMPLETE" for event in task_events)
        and transition_dumpsys_pass
        and bool(back_expected.get(case, False))
    )
    second_launch_line = launches[1].get("line_no") if len(launches) >= 2 else None
    stop_before_second_launch = any(
        row.get("event") == "GUEST_ACTIVITY_STOPPED"
        and (second_launch_line is None or row.get("line_no", -1) < second_launch_line)
        for row in target_guest
    )
    assertions = {
        "launch_request_present": len(launches) >= 2,
        "launch_request_count": len(launches),
        "launch_actions": launch_actions,
        "top_activity_correct": top_is_target,
        "physical_top_component": top_is_target,
        "activity_record_stack_order": stack_order,
        "child_cleared_by_framework": child_cleared,
        "physical_record_reused": physical_record_reused,
        "target_recreated": new_record,
        "target_destroyed": new_record,
        "child_removed": child_cleared,
        "target_reused": physical_record_reused,
        "no_second_on_create": fixture_details["counts"].get("create", -1) == 1,
        "no_on_create": fixture_details["counts"].get("create", -1) == 1,
        "no_on_new_intent": fixture_details["counts"].get("new_intent", -1) == 0,
        "stopped_before_request": stop_before_second_launch,
        "started_after_request": runtime_lifecycle["counts"].get(
            "GUEST_ACTIVITY_STARTED", 0) >= 2,
        "resumed_after_request": resumed_after_new_intent,
        "resumed": resumed_after_new_intent,
        "new_activity_created": new_record,
        "no_reuse": new_record,
        "on_new_intent": runtime_lifecycle["counts"].get(
            "GUEST_ACTIVITY_NEW_INTENT", 0) >= 1,
        "created_two_records": new_record,
        "on_create_twice": fixture_details["counts"].get("create", -1) >= 2,
        "dumpsys_gate": transition_dumpsys_pass,
        "mapping_event_present": bool(mappings),
        "framework_activity_record_present": mapping_record_present,
    }
    system_task_pass = (
        assertions["launch_request_present"] and transition_dumpsys_pass
        and top_is_target and stack_order and runtime_pass
        and (child_cleared if case in {"single_task", "clear_top_standard",
                                       "clear_top_single_top"} else True)
    )
    semantic_assertions = {
        name: bool(assertions.get(name, False))
        for name in TASK_EVIDENCE_REQUIREMENTS.get(case, ())
    }
    semantic_assertions["fixture_lifecycle_pass"] = fixture_pass
    semantic_assertions["system_task_pass"] = system_task_pass
    semantic_assertions["token_mapping_pass"] = token_mapping_pass
    semantic_assertions["back_stack_pass"] = back_stack_pass
    passed = fixture_pass and system_task_pass and token_mapping_pass and back_stack_pass
    return {
        "verdict": "FIXTURE_SEMANTIC_PASS" if passed else "FIXTURE_SEMANTIC_FAIL",
        "evidence": observation,
        "fixture_lifecycle": fixture_details,
        "runtime_lifecycle": runtime_lifecycle,
        "system_assertions": assertions,
        "semantic_assertions": semantic_assertions,
        "back_stack": {
            "pass": back_stack_pass,
            "before": before,
            "transition": transition,
            "after": after,
            "expected": back_expected.get(case, False),
        },
        "token_mapping": {
            "pass": token_mapping_pass,
            "launches": launches,
            "mappings": mappings,
            "target_physical_components": sorted(target_physical),
            "target_activity_tokens": sorted(target_tokens),
        },
        "missing_fields": [] if passed else [
            name for name, value in semantic_assertions.items() if not value
        ],
        "legacy_marker_ignored": True,
    }


def wait_for_task_event(serial: str, case: str, event: str, timeout_sec: float) -> str:
    deadline = time.monotonic() + timeout_sec
    marker = f'"case":"{case}"'
    event_marker = f'"event":"{event}"'
    latest = ""
    while time.monotonic() < deadline:
        latest = run_adb(serial, ["logcat", "-d", "-v", "threadtime"], check=False).stdout or ""
        if marker in latest and event_marker in latest:
            return latest
        time.sleep(0.15)
    return latest


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
    """Persist and later consume per-case before/transition/after evidence."""
    safe_case = re.sub(r"[^A-Za-z0-9_.-]+", "_", case)
    safe_phase = re.sub(r"[^A-Za-z0-9_.-]+", "_", phase)
    path = device_dir / f"{serial.replace(':', '_')}-{safe_case}-{safe_phase}.json"
    if phase == "transition":
        dumpsys = run_adb(serial, ["shell", "dumpsys", "activity", "activities"],
                          check=False).stdout or ""
        payload: dict[str, Any] = {"case": case, "phase": phase, "request": command or {},
                                   "dumpsys_activity_activities": dumpsys,
                                   "topResumedActivity_lines": [
                                       line for line in dumpsys.splitlines()
                                       if "topResumedActivity" in line
                                   ],
                                   "task_stack_lines": [
                                       line for line in dumpsys.splitlines()
                                       if "* Task" in line or "Hist #" in line
                                   ],
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
    for gate, comp, evidence_case, wait_sec in (
         ("standard", "com.warden.controlledsandbox.fixture.StandardTaskProbeActivity",
          "standard", 8.0),
         ("single_top", "com.warden.controlledsandbox.fixture.SingleTopProbeActivity",
          "single_top_top", 8.0),
         ("single_top_non_top", "com.warden.controlledsandbox.fixture.SingleTopNonTopProbeActivity",
          "single_top_non_top", 8.0),
         ("single_task", "com.warden.controlledsandbox.fixture.TaskSemanticsProbeActivity",
          "single_task", 12.0),
         ("clear_top_standard", "com.warden.controlledsandbox.fixture.ClearTopStandardProbeActivity",
          "clear_top_standard", 12.0),
         ("clear_top", "com.warden.controlledsandbox.fixture.ClearTopProbeActivity",
          "clear_top_single_top", 12.0),
         ("reorder_to_front", "com.warden.controlledsandbox.fixture.ReorderToFrontProbeActivity",
          "reorder_to_front", 12.0),
    ):
        run_adb(serial, ["logcat", "-c"], check=False)
        capture_activity_evidence(serial, device_dir, gate, "before")
        r = safe_debug_command(
            serial,
            ["-e", "command", "launch-component", "-e", "package",
             "com.warden.controlledsandbox.fixture", "-e", "component", comp, *TRUST],
            deadline_sec=60,
        )
        request_logcat = wait_for_task_event(
            serial, evidence_case, "BACK_REQUEST", timeout_sec=wait_sec)
        capture_activity_evidence(serial, device_dir, gate, "transition", r, request_logcat)
        task_logcat = wait_for_task_event(
            serial, evidence_case, "BACK_COMPLETE", timeout_sec=4.0)
        time.sleep(0.2)
        after_path = capture_activity_evidence(serial, device_dir, gate, "after")
        before_path = device_dir / (
            f"{serial.replace(':', '_')}-{re.sub(r'[^A-Za-z0-9_.-]+', '_', gate)}-before.json")
        transition_path = device_dir / (
            f"{serial.replace(':', '_')}-{re.sub(r'[^A-Za-z0-9_.-]+', '_', gate)}-transition.json")
        before_payload = json.loads(before_path.read_text(encoding="utf-8"))
        transition_payload = json.loads(transition_path.read_text(encoding="utf-8"))
        after_payload = json.loads(after_path.read_text(encoding="utf-8"))
        fixture_semantic = parse_task_semantic_evidence(task_logcat, evidence_case)
        semantic = evaluate_task_system_evidence(
            evidence_case, comp, fixture_semantic, before_payload,
            {**transition_payload, "lifecycle_logcat": task_logcat}, after_payload)
        task_matrix[gate] = {
            "component": comp,
            "command_status": r.get("status"),
            "semantic_verdict": semantic["verdict"],
            "structured_evidence": semantic,
            "legacy_marker_pass_not_used": True,
            "pass": r.get("status") == "PASS"
            and semantic["semantic_assertions"].get("fixture_lifecycle_pass") is True
            and semantic["semantic_assertions"].get("system_task_pass") is True
            and semantic["semantic_assertions"].get("token_mapping_pass") is True
            and semantic["semantic_assertions"].get("back_stack_pass") is True,
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

    api_order = {api: index for index, api in enumerate(REQUIRED_API_LEVELS)}
    ignored_devices = [row for row in device_rows if row[1] not in api_order]
    device_rows = [row for row in device_rows if row[1] in api_order]
    device_rows.sort(key=lambda row: (api_order[row[1]], row[0]))
    print(f"Found required-API devices (serial order API32 -> API35 -> API36): {device_rows}")
    if ignored_devices:
        print(f"Ignoring non-required API devices: {ignored_devices}")

    out_dir = artifacts_dir("a01-acceptance")
    source_identity = git_identity()
    all_results = []
    device_evidence_paths: list[Path] = []
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
        res["tested_source_commit"] = source_identity.get("commit", "")
        res["tested_tree"] = source_identity.get("tree", "")
        res["worktree_clean"] = not bool(source_identity.get("status", "").strip())
        res["evidence_sha256"] = canonical_evidence_sha256(res)
        safe_serial = re.sub(r"[^A-Za-z0-9_.-]+", "_", serial)
        device_path = out_dir / f"device-api{api or 'unknown'}-{safe_serial}.json"
        write_json(device_path, res)
        device_evidence_paths.append(device_path)
        all_results.append(res)

    final_matrix_path = out_dir / "final_matrix_evidence.json"
    final_matrix = aggregate_matrix(
        device_evidence_paths,
        output_path=final_matrix_path,
        expected_commit=source_identity.get("commit", ""),
    )
    api_matrix_pass = final_matrix["overall_pass"] is True
    api_matrix_failed = list(final_matrix.get("failed_gates", []))
    overall_pass = api_matrix_pass
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
        "final_matrix_evidence": str(final_matrix_path),
        "final_matrix": final_matrix,
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
